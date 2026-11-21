package com.usebruno.intellij.flows;

import com.google.gson.Gson;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.*;
import java.io.IOException;
import java.util.Map;

final class FlowFileOperations {
    private static final Gson JSON = new Gson();
    static VirtualFile create(Project project, VirtualFile root, String name) throws Exception {
        if (!name.matches("[A-Za-z0-9_-]+")) throw new IOException("Use letters, numbers, hyphens or underscores for the flow name.");
        if (!root.isWritable()) throw new IOException("The selected collection folder is read-only. Choose a writable folder.");
        return WriteCommandAction.writeCommandAction(project).withName("Create Bruno flow").compute(() -> {
            VirtualFile dir = root.findChild("flows");
            if (dir == null) dir = root.createChildDirectory(FlowFileOperations.class, "flows");
            if (dir.findChild(name + ".flow.bru") != null) throw new IOException("A flow with this name already exists.");
            if (root.findChild("bruno.json") == null && root.findChild("opencollection.yml") == null && root.findChild("opencollection.yaml") == null) {
                var marker = root.createChildData(FlowFileOperations.class, "bruno.json");
                VfsUtil.saveText(marker, JSON.toJson(Map.of("version", "1", "name", root.getName(), "type", "collection")));
            }
            var file = dir.createChildData(FlowFileOperations.class, name + ".flow.bru");
            VfsUtil.saveText(file, starter(name));
            return file;
        });
    }
    static boolean yaml(VirtualFile root) { return root.findChild("opencollection.yml")!=null || root.findChild("opencollection.yaml")!=null; }
    static VirtualFile collectionRoot(VirtualFile selected) {
        for (var folder=selected;folder!=null;folder=folder.getParent()) if (folder.findChild("bruno.json")!=null || yaml(folder)) return folder;
        return selected;
    }
    static VirtualFile importJson(Project project,VirtualFile root,FlowJsonImport.Prepared prepared) throws Exception {
        return WriteCommandAction.writeCommandAction(project).withName("Import Bruno flow JSON").compute(() -> {
            if (!root.isValid() || !root.isDirectory() || !root.isWritable()) throw new IOException("Choose a writable collection folder.");
            if (yaml(root)!=prepared.yaml()) throw new IOException("Collection format changed; choose the destination again.");
            VirtualFile dir=root.findChild("flows"),createdDir=null,marker=null,file=null;
            if (dir!=null && dir.is(VFileProperty.SYMLINK)) throw new IOException("The flows folder must not be a symbolic link.");
            if (dir!=null && !dir.isDirectory()) throw new IOException("The destination already contains a file named flows.");
            String extension=prepared.yaml() ? ".flow.yml" : ".flow.bru";
            String name=prepared.stem()+extension; int suffix=2;
            while (dir!=null && dir.findChild(name)!=null) name=prepared.stem()+"-"+(suffix++)+extension;
            try {
                if (dir==null) { dir=root.createChildDirectory(FlowFileOperations.class,"flows"); createdDir=dir; }
                if (root.findChild("bruno.json")==null && !yaml(root)) {
                    marker=root.createChildData(FlowFileOperations.class,"bruno.json");
                    VfsUtil.saveText(marker,JSON.toJson(Map.of("version","1","name",root.getName(),"type","collection")));
                }
                file=dir.createChildData(FlowFileOperations.class,name); VfsUtil.saveText(file,prepared.content()); return file;
            } catch (Exception error) {
                try { if (file!=null && file.isValid()) file.delete(FlowFileOperations.class); } catch (Exception cleanup) { error.addSuppressed(cleanup); }
                try { if (marker!=null && marker.isValid()) marker.delete(FlowFileOperations.class); } catch (Exception cleanup) { error.addSuppressed(cleanup); }
                try { if (createdDir!=null && createdDir.isValid() && createdDir.getChildren().length==0) createdDir.delete(FlowFileOperations.class); } catch (Exception cleanup) { error.addSuppressed(cleanup); }
                throw error;
            }
        });
    }
    static String starter(String name) {
        return "meta {\n  name: " + name + "\n  type: flow\n  seq: 1\n}\n\nflow {\n  data: '''\n"
                + "    {\"nodes\":[{\"id\":\"start\",\"type\":\"start\",\"position\":{\"x\":0,\"y\":100},\"data\":{}},"
                + "{\"id\":\"end\",\"type\":\"end\",\"position\":{\"x\":300,\"y\":100},\"data\":{}}],"
                + "\"edges\":[{\"id\":\"start-end\",\"source\":\"start\",\"target\":\"end\"}],\"config\":{}}\n  '''\n}\n";
    }
    static void delete(Project project, VirtualFile file) throws Exception {
        if (file.isDirectory() || !file.getName().matches(".*\\.flow\\.(bru|yml|yaml)")) throw new IOException("Select a flow file to delete.");
        WriteCommandAction.writeCommandAction(project).withName("Delete Bruno flow").run(() -> file.delete(FlowFileOperations.class));
    }
}
