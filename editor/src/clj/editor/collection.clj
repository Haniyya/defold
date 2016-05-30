(ns editor.collection
  (:require [clojure.java.io :as io]
            [editor.core :as core]
            [editor.protobuf :as protobuf]
            [dynamo.graph :as g]
            [editor.graph-util :as gu]
            [editor.dialogs :as dialogs]
            [editor.game-object :as game-object]
            [editor.geom :as geom]
            [editor.handler :as handler]
            [editor.math :as math]
            [editor.defold-project :as project]
            [editor.scene :as scene]
            [editor.scene-tools :as scene-tools]
            [editor.outline :as outline]
            [editor.types :as types]
            [editor.workspace :as workspace]
            [editor.outline :as outline]
            [editor.resource :as resource]
            [editor.validation :as validation]
            [editor.gl.pass :as pass]
            [editor.progress :as progress]
            [editor.properties :as properties]
            [clojure.string :as str])
  (:import [com.dynamo.gameobject.proto GameObject$CollectionDesc]
           [com.dynamo.graphics.proto Graphics$Cubemap Graphics$TextureImage Graphics$TextureImage$Image Graphics$TextureImage$Type]
           [com.dynamo.proto DdfMath$Point3 DdfMath$Quat]
           [com.jogamp.opengl.util.awt TextRenderer]
           [editor.types Region Animation Camera Image TexturePacking Rect EngineFormatTexture AABB TextureSetAnimationFrame TextureSetAnimation TextureSet]
           [java.awt.image BufferedImage]
           [java.io PushbackReader]
           [javax.media.opengl GL GL2 GLContext GLDrawableFactory]
           [javax.media.opengl.glu GLU]
           [javax.vecmath Matrix4d Point3d Quat4d Vector3d Vector4d]
           [org.apache.commons.io FilenameUtils]))

(set! *warn-on-reflection* true)

(def collection-icon "icons/32/Icons_09-Collection.png")
(def path-sep "/")

(defn- gen-embed-ddf [id child-ids position ^Quat4d rotation-q4 scale save-data]
  {:id id
   :children child-ids
   ; TODO properties
   :data (:content save-data)
   :position position
   :rotation (math/vecmath->clj rotation-q4)
   :scale3 scale})

(defn- gen-ref-ddf [id child-ids position ^Quat4d rotation-q4 scale path]
  {:id id
   :children child-ids
   ; TODO properties
   :prototype (resource/resource->proj-path path)
   :position position
   :rotation (math/vecmath->clj rotation-q4)
   :scale3 scale})

(defn- assoc-deep [scene keyword new-value]
  (let [new-scene (assoc scene keyword new-value)]
    (if (:children scene)
      (assoc new-scene :children (map #(assoc-deep % keyword new-value) (:children scene)))
      new-scene)))

(defn- label-sort-by-fn [v]
  (when-let [label (:label v)] (str/lower-case label)))

(defn- outline-sort-by-fn [v]
  [(:name (g/node-type* (:node-id v))) (label-sort-by-fn v)])

(g/defnode InstanceNode
  (inherits outline/OutlineNode)
  (property id g/Str)
  (property url g/Str
            (value (g/fnk [base-url id] (format "%s/%s" (or base-url "") id)))
            (dynamic read-only? (g/always true)))
  (input base-url g/Str))

(defn- child-go-go [go-id child-id]
  (for [[from to] [[:id :child-ids]
                   [:node-outline :child-outlines]
                   [:scene :child-scenes]]]
    (g/connect child-id from go-id to)))

(defn- child-coll-any [coll-id child-id]
  (concat
    (for [[from to] [[:node-outline :child-outlines]
                     [:scene :child-scenes]]]
      (g/connect child-id from coll-id to))
    (for [[from to] [[:url :base-url]]]
      (g/connect coll-id from child-id to))))

(defn- attach-coll-go [coll-id child-id ddf-label]
  (concat
    (g/connect child-id :_node-id coll-id :nodes)
    (for [[from to] [[:ddf-message ddf-label]
                     [:build-targets :dep-build-targets]
                     [:id :ids]]]
      (g/connect child-id from coll-id to))))

(defn- attach-coll-ref-go [coll-id child-id]
  (attach-coll-go coll-id child-id :ref-inst-ddf))

(defn- attach-coll-embedded-go [coll-id child-id]
  (attach-coll-go coll-id child-id :embed-inst-ddf))

(defn- attach-coll-coll [coll-id child-id]
  (concat
    (g/connect child-id :_node-id coll-id :nodes)
    (let [conns [[:ddf-message :ref-coll-ddf]
                 [:id :ids]
                 [:build-targets :sub-build-targets]]]
      (for [[from to] conns]
        (g/connect child-id from coll-id to)))))

(def EmbeddedGOInstanceNode nil)
(def ReferencedGOInstanceNode nil)

(defn- go-id->node-ids [go-id]
  (let [collection (core/scope go-id)]
    (g/node-value collection :ids)))

(g/defnk produce-go-outline [_node-id id node-outline-label source-outline child-outlines]
  (let [coll-id (core/scope _node-id)]
    (-> source-outline
      (merge {:node-id _node-id
              :label node-outline-label
              :icon game-object/game-object-icon
              :children (into (vec (sort-by label-sort-by-fn (:children source-outline))) child-outlines)})
      (update :child-reqs (fn [c] (conj (if c c [])
                                        {:node-type ReferencedGOInstanceNode
                                         :tx-attach-fn (fn [self-id child-id]
                                                         (concat
                                                           (g/update-property child-id :id outline/resolve-id (go-id->node-ids self-id))
                                                           (attach-coll-ref-go coll-id child-id)
                                                           (child-go-go self-id child-id)))}
                                        {:node-type EmbeddedGOInstanceNode
                                         :tx-attach-fn (fn [self-id child-id]
                                                         (concat
                                                           (g/update-property child-id :id outline/resolve-id (go-id->node-ids self-id))
                                                           (attach-coll-embedded-go coll-id child-id)
                                                           (child-go-go self-id child-id)))}))))))

(defn- source-outline-subst [err]
  ;; TODO: embed error so can warn in outline
  ;; outline content not really used, only children if any.
  {:node-id 0
   :icon ""
   :label ""})

(g/defnode GameObjectInstanceNode
  (inherits scene/ScalableSceneNode)
  (inherits InstanceNode)

  (input source-id g/Any :cascade-delete)
  (input properties g/Any)
  (input build-targets g/Any)
  (input scene g/Any)
  (input child-scenes g/Any :array)
  (input child-ids g/Str :array)

  (input source-overrides g/Any)
  (input source-properties g/Properties :substitute {:properties {}})
  (input source-outline outline/OutlineData :substitute source-outline-subst)
  (output source-outline outline/OutlineData (g/fnk [source-outline] source-outline))

  (output node-outline outline/OutlineData :cached produce-go-outline)
  (output ddf-message g/Any :abstract)
  (output node-outline-label g/Str (g/fnk [id] id))
  (output build-targets g/Any (g/fnk [build-targets ddf-message transform] (let [target (first build-targets)]
                                                                             [(assoc target :instance-data {:resource (:resource target)
                                                                                                            :instance-msg ddf-message
                                                                                                            :transform transform})])))

  (output scene g/Any :cached (g/fnk [_node-id transform scene child-scenes]
                                     (let [aabb (reduce #(geom/aabb-union %1 (:aabb %2)) (or (:aabb scene) (geom/null-aabb)) child-scenes)
                                           aabb (geom/aabb-transform (geom/aabb-incorporate aabb 0 0 0) transform)]
                                       (merge-with concat
                                                   (assoc (assoc-deep scene :node-id _node-id)
                                                          :transform transform
                                                          :aabb aabb
                                                          :renderable {:passes [pass/selection]})
                                                   {:children child-scenes})))))

(g/defnode EmbeddedGOInstanceNode
  (inherits GameObjectInstanceNode)

  (display-order [:id :url scene/ScalableSceneNode])

  (input save-data g/Any)

  (output ddf-message g/Any :cached (g/fnk [id child-ids position ^Quat4d rotation-q4 scale save-data]
                                           (gen-embed-ddf id child-ids position rotation-q4 scale save-data))))

(defn- or-go-traverse? [basis [src-id src-label tgt-id tgt-label]]
  (if (g/node-instance? basis game-object/ComponentNode src-id)
    (some-> (g/node-value src-id :source-resource :basis basis)
      resource/resource-type
      :tags
      (contains? :overridable-properties))))

(g/defnode ReferencedGOInstanceNode
  (inherits GameObjectInstanceNode)

  (property path g/Any
            (dynamic edit-type (g/fnk [source-resource]
                                      {:type (g/protocol resource/Resource)
                                       :ext (some-> source-resource resource/resource-type :ext)
                                       :to-type (fn [v] (:resource v))
                                       :from-type (fn [r] {:resource r :overrides {}})}))
            (value (g/fnk [source-resource property-overrides]
                          {:resource source-resource
                           :overrides property-overrides}))
            (set (fn [basis self old-value new-value]
                   (concat
                     (if-let [old-source (g/node-value self :source-id :basis basis)]
                       (g/delete-node old-source)
                       [])
                     (let [new-resource (:resource new-value)]
                       (let [project (project/get-project self)]
                         (project/connect-resource-node project new-resource self []
                                                        (fn [go-node]
                                                          (let [override (g/override basis go-node {:traverse? or-go-traverse?})
                                                                id-mapping (:id-mapping override)
                                                                or-node (get id-mapping go-node)
                                                                id-mapping (comp id-mapping (g/node-value go-node :component-ids :basis basis))]
                                                            (concat
                                                              (:tx-data override)
                                                              (let [outputs (g/output-labels (:node-type (resource/resource-type new-resource)))]
                                                                (for [[from to] [[:_node-id      :source-id]
                                                                                 [:resource      :source-resource]
                                                                                 [:node-outline  :source-outline]
                                                                                 [:build-targets :build-targets]
                                                                                 [:scene         :scene]]
                                                                      :when (contains? outputs from)]
                                                                  (g/connect or-node from self to)))
                                                              (for [[from to] [[:url :base-url]]]
                                                                (g/connect self from or-node to))
                                                              (for [[id p] (:overrides new-value)
                                                                    [label value] p
                                                                    :let [comp-id (id-mapping id)]]
                                                                (g/set-property comp-id label value)))))))))))
            (validate (g/fnk [path]
                             (when (nil? path)
                               (g/error-warning "Missing prototype")))))

  (display-order [:id :url :path scene/ScalableSceneNode])

  (input source-resource (g/protocol resource/Resource))
  (output property-overrides g/Any :cached
          (g/fnk [source-properties]
                 (into {}
                       (map (fn [[key p]] [key (:value p)])
                            (filter (fn [[_ p]] (contains? p :original-value))
                                    (:properties source-properties))))))
  (output node-outline-label g/Str (g/fnk [id source-resource] (format "%s (%s)" id (resource/resource->proj-path source-resource))))
  (output ddf-message g/Any :cached (g/fnk [id child-ids source-resource position ^Quat4d rotation-q4 scale]
                                           (gen-ref-ddf id child-ids position rotation-q4 scale source-resource))))

(g/defnk produce-proto-msg [name ref-inst-ddf embed-inst-ddf ref-coll-ddf]
  {:name name
   :instances ref-inst-ddf
   :embedded-instances embed-inst-ddf
   :collection-instances ref-coll-ddf})

(g/defnk produce-save-data [resource proto-msg]
  {:resource resource
   :content (protobuf/map->str GameObject$CollectionDesc proto-msg)})

(defn- externalize [inst-data resources]
  (map (fn [data]
         (let [{:keys [resource instance-msg transform]} data
               instance-msg (dissoc instance-msg :data)
               resource (get resources resource)
               pos (Point3d.)
               rot (Quat4d.)
               scale (Vector3d.)
               _ (math/split-mat4 transform pos rot scale)]
           (merge instance-msg
                  {:id (str path-sep (:id instance-msg))
                   :prototype (resource/proj-path resource)
                   :children (map #(str path-sep %) (:children instance-msg))
                   :position (math/vecmath->clj pos)
                   :rotation (math/vecmath->clj rot)
                   :scale3 (math/vecmath->clj scale)}))) inst-data))

(defn build-collection [self basis resource dep-resources user-data]
  (let [{:keys [name instance-data]} user-data
        instance-msgs (externalize instance-data dep-resources)
        msg {:name name
             :instances instance-msgs}]
    {:resource resource :content (protobuf/map->bytes GameObject$CollectionDesc msg)}))

(g/defnk produce-build-targets [_node-id name resource proto-msg sub-build-targets dep-build-targets]
  (let [sub-build-targets (flatten sub-build-targets)
        dep-build-targets (flatten dep-build-targets)
        instance-data (map :instance-data dep-build-targets)
        instance-data (reduce concat instance-data (map #(get-in % [:user-data :instance-data]) sub-build-targets))]
    [{:node-id _node-id
      :resource (workspace/make-build-resource resource)
      :build-fn build-collection
      :user-data {:name name :instance-data instance-data}
      :deps (vec (reduce into dep-build-targets (map :deps sub-build-targets)))}]))

(def CollectionInstanceNode nil)

(g/defnk produce-coll-outline [_node-id child-outlines]
  {:node-id _node-id
   :label "Collection"
   :icon collection-icon
   :children (vec (sort-by outline-sort-by-fn child-outlines))
   :child-reqs [{:node-type ReferencedGOInstanceNode
                 :tx-attach-fn (fn [self-id child-id]
                                 (concat
                                   (g/update-property child-id :id outline/resolve-id (g/node-value self-id :ids))
                                   (attach-coll-ref-go self-id child-id)
                                   (child-coll-any self-id child-id)))}
                {:node-type EmbeddedGOInstanceNode
                 :tx-attach-fn (fn [self-id child-id]
                                 (concat
                                   (g/update-property child-id :id outline/resolve-id (g/node-value self-id :ids))
                                   (attach-coll-embedded-go self-id child-id)
                                   (child-coll-any self-id child-id)))}
                {:node-type CollectionInstanceNode
                 :tx-attach-fn (fn [self-id child-id]
                                 (concat
                                   (g/update-property child-id :id outline/resolve-id (g/node-value self-id :ids))
                                   (attach-coll-coll self-id child-id)
                                   (child-coll-any self-id child-id)))}]})

(g/defnode CollectionNode
  (inherits project/ResourceNode)

  (property name g/Str)

  (input ref-inst-ddf g/Any :array)
  (input embed-inst-ddf g/Any :array)
  (input ref-coll-ddf g/Any :array)
  (input child-scenes g/Any :array)
  (input ids g/Str :array)
  (input sub-build-targets g/Any :array)
  (input dep-build-targets g/Any :array)
  (input base-url g/Str)

  (output url g/Str (g/fnk [base-url] base-url))
  (output proto-msg g/Any :cached produce-proto-msg)
  (output save-data g/Any :cached produce-save-data)
  (output build-targets g/Any :cached produce-build-targets)
  (output node-outline outline/OutlineData :cached produce-coll-outline)
  (output scene g/Any :cached (g/fnk [_node-id child-scenes]
                                     {:node-id _node-id
                                      :children child-scenes
                                      :aabb (reduce geom/aabb-union (geom/null-aabb) (filter #(not (nil? %)) (map :aabb child-scenes)))})))

(defn- flatten-instance-data [data base-id ^Matrix4d base-transform all-child-ids]
  (let [{:keys [resource instance-msg ^Matrix4d transform]} data
        is-child? (contains? all-child-ids (:id instance-msg))
        instance-msg {:id (str base-id (:id instance-msg))
                      :children (map #(str base-id %) (:children instance-msg))}
        transform (if is-child?
                    transform
                    (doto (Matrix4d. transform) (.mul base-transform transform)))]
    {:resource resource :instance-msg instance-msg :transform transform}))

(g/defnk produce-coll-inst-build-targets [id transform build-targets]
  (let [base-id (str id path-sep)
        instance-data (get-in build-targets [0 :user-data :instance-data])
        child-ids (reduce (fn [child-ids data] (into child-ids (:children (:instance-msg data)))) #{} instance-data)]
    (assoc-in build-targets [0 :user-data :instance-data] (map #(flatten-instance-data % base-id transform child-ids) instance-data))))

(g/defnk produce-coll-inst-outline [_node-id id source-resource source-outline source]
  (-> source-outline
    (assoc :node-id _node-id
      :label (format "%s (%s)" id (resource/resource->proj-path source-resource))
      :icon collection-icon)
    (update :child-reqs (fn [reqs]
                          (mapv (fn [req] (update req :tx-attach-fn #(fn [self-id child-id]
                                                                       (% source child-id))))
                            ; TODO - temp blocked because of risk for graph cycles
                            ; If it's dropped on another instance referencing the same collection, it blows up
                                (filter (fn [req] (not= CollectionInstanceNode (:node-type req))) reqs))))))

(g/defnode CollectionInstanceNode
  (inherits scene/ScalableSceneNode)
  (inherits InstanceNode)

  (property path (g/protocol resource/Resource)
    (value (gu/passthrough source-resource))
    (set (project/gen-resource-setter [[:_node-id      :source]
                                       [:resource      :source-resource]
                                       [:node-outline  :source-outline]
                                       [:scene         :scene]
                                       [:build-targets :build-targets]]
                                      (fn [basis self n] (g/connect self :url n :base-url))))
    (validate (validation/validate-resource path "Missing prototype" [scene])))

  (display-order [:id :url :path scene/ScalableSceneNode])

  (input source g/Any)
  (input source-resource (g/protocol resource/Resource))
  (input scene g/Any)
  (input build-targets g/Any)

  (input source-outline outline/OutlineData :substitute source-outline-subst)
  (output source-outline outline/OutlineData (g/fnk [source-outline] source-outline))

  (output node-outline outline/OutlineData :cached produce-coll-inst-outline)
  (output ddf-message g/Any :cached (g/fnk [id source-resource position ^Quat4d rotation-q4 scale]
                                           {:id id
                                            :collection (resource/resource->proj-path source-resource)
                                            :position position
                                            :rotation (math/vecmath->clj rotation-q4)
                                            :scale3 scale}))
  (output scene g/Any :cached (g/fnk [_node-id transform scene]
                                     (assoc (assoc-deep scene :node-id _node-id)
                                           :transform transform
                                           :aabb (geom/aabb-transform (or (:aabb scene) (geom/null-aabb)) transform)
                                           :renderable {:passes [pass/selection]})))
  (output build-targets g/Any produce-coll-inst-build-targets))

(defn- gen-instance-id [coll-node base]
  (let [ids (g/node-value coll-node :ids)]
    (loop [postfix 0]
      (let [id (if (= postfix 0) base (str base postfix))]
        (if (empty? (filter #(= id %) ids))
          id
          (recur (inc postfix)))))))


(defn- make-ref-go [self project source-resource id position rotation scale child? overrides]
  (let [path {:resource source-resource
              :overrides overrides}]
    (g/make-nodes (g/node-id->graph-id self)
                  [go-node [ReferencedGOInstanceNode :id id :path path :position position :rotation rotation :scale scale]]
                  (attach-coll-ref-go self go-node)
                  (if child?
                    (child-coll-any self go-node)
                    []))))

(defn- single-selection? [selection]
  (= 1 (count selection)))

(defn- selected-collection? [selection]
  (g/node-instance? CollectionNode (first selection)))

(defn- selected-embedded-instance? [selection]
  (let [node (first selection)]
    (g/node-instance? EmbeddedGOInstanceNode node)))

(defn add-game-object-file [coll-node resource]
  (let [project (project/get-project coll-node)
        base (FilenameUtils/getBaseName (resource/resource-name resource))
        id (gen-instance-id coll-node base)
        op-seq (gensym)
        [go-node] (g/tx-nodes-added
                    (g/transact
                      (concat
                        (g/operation-label "Add Game Object")
                        (g/operation-sequence op-seq)
                        (make-ref-go coll-node project resource id [0 0 0] [0 0 0] [1 1 1] true []))))]
    ; Selection
    (g/transact
      (concat
        (g/operation-sequence op-seq)
        (g/operation-label "Add Game Object")
        (project/select project [go-node])))))

(handler/defhandler :add-from-file :global
  (active? [selection] (and (single-selection? selection)
                            (or (selected-collection? selection)
                                (selected-embedded-instance? selection))))
  (label [selection] (cond
                       (selected-collection? selection) "Add Game Object File"
                       (selected-embedded-instance? selection) "Add Component File"))
  (run [workspace selection]
       (cond
         (selected-collection? selection) (when-let [resource (first (dialogs/make-resource-dialog workspace {:ext "go" :title "Select Game Object File"}))]
                                            (let [coll-node (first selection)]
                                              (add-game-object-file coll-node resource)))
         (selected-embedded-instance? selection) (game-object/add-component-handler workspace (g/node-value (first selection) :source-id)))))

(defn- make-embedded-go [self project type data id position rotation scale child? select?]
  (let [graph (g/node-id->graph-id self)
        resource (project/make-embedded-resource project type data)]
    (g/make-nodes graph [go-node [EmbeddedGOInstanceNode :id id :position position :rotation rotation :scale scale]]
      (if select?
        (project/select project [go-node])
        [])
      (let [tx-data (project/make-resource-node graph project resource true
                                                {go-node [[:_node-id :source-id]
                                                          [:node-outline :source-outline]
                                                          [:save-data :save-data]
                                                          [:build-targets :build-targets]
                                                          [:scene :scene]]
                                                 self [[:_node-id :nodes]]}
                                                (fn [n] (g/connect go-node :url n :base-url)))]
        (concat
          tx-data
          (if (empty? tx-data)
            []
            (concat
              (attach-coll-embedded-go self go-node)
              (if child?
                (child-coll-any self go-node)
                []))))))))

(defn- add-game-object [selection]
  (let [coll-node     (first selection)
        project       (project/get-project coll-node)
        workspace     (:workspace (g/node-value coll-node :resource))
        ext           "go"
        resource-type (workspace/get-resource-type workspace ext)
        template      (workspace/template resource-type)
        id (gen-instance-id coll-node ext)]
    (g/transact
      (concat
        (g/operation-label "Add Game Object")
        (make-embedded-go coll-node project ext template id [0 0 0] [0 0 0] [1 1 1] true true)))))

(handler/defhandler :add :global
  (active? [selection] (and (single-selection? selection)
                            (or (selected-collection? selection)
                                (selected-embedded-instance? selection))))
  (label [selection user-data] (cond
                                 (selected-collection? selection) "Add Game Object"
                                 (selected-embedded-instance? selection) (game-object/add-embedded-component-label user-data)))
  (options [selection user-data] (when (selected-embedded-instance? selection)
                                   (let [source (g/node-value (first selection) :source-id)
                                         workspace (:workspace (g/node-value source :resource))]
                                     (game-object/add-embedded-component-options source workspace user-data))))
  (run [selection user-data] (cond
                               (selected-collection? selection) (add-game-object selection)
                               (selected-embedded-instance? selection) (game-object/add-embedded-component-handler user-data))))

(defn- add-collection-instance [self source-resource id position rotation scale]
  (let [project (project/get-project self)]
    (g/make-nodes (g/node-id->graph-id self)
                  [coll-node [CollectionInstanceNode :id id :path source-resource
                              :position position :rotation rotation :scale scale]]
                  (attach-coll-coll self coll-node)
                  (child-coll-any self coll-node))))

(handler/defhandler :add-secondary-from-file :global
  (active? [selection] (and (single-selection? selection) (selected-collection? selection)))
  (label [] "Add Collection File")
  (run [selection] (let [coll-node     (first selection)
                         project       (project/get-project coll-node)
                         workspace     (:workspace (g/node-value coll-node :resource))
                         ext           "collection"
                         resource-type (workspace/get-resource-type workspace ext)]
                     (when-let [resource (first (dialogs/make-resource-dialog workspace {:ext ext :title "Select Collection File"}))]
                       (let [base (FilenameUtils/getBaseName (resource/resource-name resource))
                             id (gen-instance-id coll-node base)
                             op-seq (gensym)
                             [coll-inst-node] (g/tx-nodes-added
                                               (g/transact
                                                (concat
                                                 (g/operation-label "Add Collection")
                                                 (g/operation-sequence op-seq)
                                                 (add-collection-instance coll-node resource id [0 0 0] [0 0 0] [1 1 1]))))]
                                        ; Selection
                         (g/transact
                          (concat
                           (g/operation-sequence op-seq)
                           (g/operation-label "Add Collection")
                           (project/select project [coll-inst-node]))))))))

(defn- v4->euler [v]
  (math/quat->euler (doto (Quat4d.) (math/clj->vecmath v))))

(defn load-collection [project self resource]
  (let [collection (protobuf/read-text GameObject$CollectionDesc resource)
        project-graph (g/node-id->graph-id project)
        go-resources (map :prototype (:instances collection))
        go-load-data  (project/load-resource-nodes project
                                                   (->> go-resources
                                                     (map #(project/get-resource-node project %))
                                                     (remove nil?))
                                                   progress/null-render-progress!)]
    (concat
      go-load-data
      (g/set-property self :name (:name collection))
      (let [tx-go-creation (flatten
                             (concat
                               (for [game-object (:instances collection)
                                     :let [; TODO - fix non-uniform hax
                                           scale (:scale game-object)
                                           source-resource (workspace/resolve-resource resource (:prototype game-object))
                                           properties (into {} (map (fn [m] [(:id m) (into {} (map (fn [p] [(properties/user-name->key (:id p)) (properties/str->go-prop (:value p) (:type p))]) (:properties m)))]) (:component-properties game-object)))]]
                                 (make-ref-go self project source-resource (:id game-object) (:position game-object)
                                   (v4->euler (:rotation game-object)) [scale scale scale] false properties))
                               (for [embedded (:embedded-instances collection)
                                     :let [; TODO - fix non-uniform hax
                                           scale (:scale embedded)]]
                                 (make-embedded-go self project "go" (:data embedded) (:id embedded)
                                   (:position embedded)
                                   (v4->euler (:rotation embedded))
                                   [scale scale scale] false false))))
            new-instance-data (filter #(and (= :create-node (:type %)) (g/node-instance*? GameObjectInstanceNode (:node %))) tx-go-creation)
            id->nid (into {} (map #(do [(get-in % [:node :id]) (g/node-id (:node %))]) new-instance-data))
            child->parent (into {} (map #(do [% nil]) (keys id->nid)))
            rev-child-parent-fn (fn [instances] (into {} (mapcat (fn [inst] (map #(do [% (:id inst)]) (:children inst))) instances)))
            child->parent (merge child->parent (rev-child-parent-fn (concat (:instances collection) (:embedded-instances collection))))]
        (concat
          tx-go-creation
          (for [[child parent] child->parent
                :let [child-id (id->nid child)
                      parent-id (if parent (id->nid parent) self)]]
            (if parent
              (child-go-go parent-id child-id)
              (child-coll-any self child-id)))))
      (for [coll-instance (:collection-instances collection)
            :let [; TODO - fix non-uniform hax
                  scale (:scale coll-instance)
                  source-resource (workspace/resolve-resource resource (:collection coll-instance))]]
        (add-collection-instance self source-resource (:id coll-instance) (:position coll-instance)
          (v4->euler (:rotation coll-instance)) [scale scale scale])))))

(defn register-resource-types [workspace]
  (workspace/register-resource-type workspace
                                    :ext "collection"
                                    :label "Collection"
                                    :node-type CollectionNode
                                    :load-fn load-collection
                                    :icon collection-icon
                                    :view-types [:scene :text]
                                    :view-opts {:scene {:grid true}}))
