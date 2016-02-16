package com.dynamo.bob.pipeline;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;

import com.dynamo.bob.BuilderParams;
import com.dynamo.bob.CopyBuilder;
import com.dynamo.bob.Task;
import com.dynamo.bob.fs.IResource;

@BuilderParams(name = "FragmentProgram", inExts = ".fp", outExt = ".fpc")
public class FragmentProgramBuilder extends CopyBuilder {

    @Override
    public void build(Task<Void> task) throws IOException {
        IResource in = task.getInputs().get(0);
        IResource out = task.getOutputs().get(0);

        ByteArrayOutputStream os = new ByteArrayOutputStream(16 * 1024);
        PrintWriter writer = new PrintWriter(os);
        writer.println("#ifdef GL_ES");
        writer.println("precision mediump float;");
        writer.println("#endif");
        writer.println("#ifndef GL_ES");
        writer.println("#define lowp");
        writer.println("#define mediump");
        writer.println("#define highp");
        writer.println("#endif");
        writer.println("#line 0");

        writer.close();
        os.write(in.getContent());
        os.close();
        out.setContent(os.toByteArray());
    }
}
