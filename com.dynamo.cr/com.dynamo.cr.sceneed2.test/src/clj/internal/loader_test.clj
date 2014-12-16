(ns internal.loader-test
  (:require [clojure.test :refer :all]
            [dynamo.node :as n]
            [dynamo.project :as p]
            [dynamo.system :as ds]
            [dynamo.system.test-support :refer :all]
            [dynamo.types :as t]
            [internal.query :as iq]
            [schema.core :as s]))

(n/defnode DummyNode
  (property project (s/protocol t/NamingContext))
  (property filename s/Str))

(defn- dummy
  [proj file]
  (ds/transactional
    (ds/in proj
      (ds/add
        (make-dummy-node :project proj ::p/filename file)))))

(defn- bomb
  [& _]
  (throw (ex-info "Should not have been called" {})))

(deftest reuse-same-node
  (with-clean-world
    (let [project (ds/transactional (ds/add (p/make-project)))
          node1   (p/node-by-filename project "test-resource.txt" dummy)
          node2   (p/node-by-filename project "test-resource.txt" bomb)]
      (is (= (:_id node1) (:_id node2))))))
