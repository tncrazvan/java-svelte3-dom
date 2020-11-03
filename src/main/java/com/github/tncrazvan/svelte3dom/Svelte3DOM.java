/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.github.tncrazvan.svelte3dom;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;

/**
 *
 * @author Administrator
 */
public class Svelte3DOM {
    protected Context context;
    private String internal;
    
    public Svelte3DOM(Path wd){
        try {
            context = Context
                    .newBuilder()
                    .currentWorkingDirectory(wd)
                    .allowAllAccess(true)
                    .allowIO(true)
                    .build();
            
            String tools =
                    "const System = Java.type('java.lang.System');" +
                    "const FileReaderJS = Java.type('com.github.tncrazvan.svelte3dom.FileReaderJS');" +
                    "load('./node_modules/jvm-npm/src/main/javascript/jvm-npm.js');" +
                    "const { compile } = require('./compiler.js');" +
                    "const { componentDom } = require('./compileDom.js');"
                    ;
            
            context.eval("js",tools);
            
            Value dom = context.eval("js", "(function(service){dom=service;});");
            dom.executeVoid(this);
            
            context.eval("js","console.log('JavaScript initialized.');");
            internal = resolveInternal();
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }
    
    public Context getContext(){
        return context;
    }
    
    
    //compiling stuff
    
    private void require(String[] names, String path, HashMap<String,HashMap<String,String>> imports) throws IOException{
        if(!imports.containsKey(path))
            imports.put(path, new HashMap<>());
        
        HashMap<String,String> importedPath = imports.get(path);
        
        for(String item : names){
            item = item.trim();
            if(path.endsWith(".svelte")){
                if(imports.get(path).containsKey(item)){
                    continue;
                }
                String value = 
                        PATTERN_USE_STRICT
                        .matcher(
                            compileFile(path,"UTF-8",imports,false)
                        )
                        .replaceAll("");
                importedPath.put(
                    item, 
                    "const "+item+" =(function (){\n" +
                        "let exports={};\n" +
                        value + "\n" +
                        "return Component;\n" +
                    "})();"
                );
            }else{
                
                if(names.length > 1) {
                    String script = "(function (){const {"+item+"} = require('"+path+"'); if(!"+item+") FileReaderJS.readString('"+path+"'); else return "+item+".toString();})";
                    String value = context.eval("js",script).execute().asString();
                    importedPath.put(item, value);
                }else
                    importedPath.put(item, Files.readString(Path.of(path)));
            }
        }
    }
    
    
    private static final Pattern PATTERN_SVELTE_ITEMS = Pattern.compile("(?<=const)(?<=\\{)?.*(?=\\})?(?==)",Pattern.MULTILINE|Pattern.DOTALL);
    private static final Pattern PATTERN_SVELTE_PATH = Pattern.compile("(?<=require\\(\\\"|').*(?=\\\"|'\\);)",Pattern.MULTILINE|Pattern.DOTALL);
    private static final Pattern PATTERN_REQUIRES = Pattern.compile("^const .*require\\([\"'].*[\"']\\);",Pattern.MULTILINE);
    private static final Pattern PATTERN_USE_STRICT = Pattern.compile("[\"']use strict[\"'];",Pattern.MULTILINE);
    
    private void parseRequires(String requires, HashMap<String,HashMap<String,String>> imports) throws IOException{
        Matcher mpath = PATTERN_SVELTE_PATH.matcher(requires);
        if(!mpath.find())
            return;
        String path = mpath.group();
        Matcher mitems = PATTERN_SVELTE_ITEMS.matcher(requires);
        if(!mitems.find())
            return;
        String sitems = mitems
                .group()
                .replaceAll("[\\{|\\}]+", "")
                .replaceAll(".*default:", "");
        String[] items = sitems.split(",");
        require(items, path, imports);
    }
    
    
    public static class ResolvedImport{
        String[] names;
        String path;
    }
    public String compileFile(String filename,String charset) throws IOException{
        return compileFile(filename, charset, new HashMap<>(), true);
    }
    public String compileFile(String filename,String charset, HashMap<String,HashMap<String,String>> imports) throws IOException{
        return compileFile(filename, charset, imports, true);
    }
    public String compileFile(String filename, String charset, HashMap<String,HashMap<String,String>> imports, boolean addGlobals) throws IOException{
        return compile(
                Files.readString(Path.of(filename), Charset.forName(charset)),
                imports,
                addGlobals
        );
    }
    
    public String compile(String source) throws IOException{
        return compile(source, new HashMap<>(), true);
    }
    
    public String compile(String source, HashMap<String,HashMap<String,String>> imports) throws IOException{
        return compile(source, imports, true);
    }
    
    public String compile(String source, HashMap<String,HashMap<String,String>> imports, boolean addGlobals) throws IOException{
        Value app = context.eval("js", "(function(source){return compile(source,{generate:'dom',format:'cjs'}).js.code;});");
        String compiledContents = app.execute(source).asString();
        
        
        Matcher m = PATTERN_REQUIRES.matcher(compiledContents);
        while(m.find()){
            String item = m.group();
            //System.out.println("import:"+item);
            parseRequires(item,imports);
        }
        compiledContents = m.replaceAll("");
        
        String globals = addGlobals?String.join("\n", 
            "let exports = {}",
            "let flushing = false;",
            "const seen_callbacks = new Set();",
            "const dirty_components = [];",
            "const intros = { enabled: false };",
            "const binding_callbacks = [];",
            "const render_callbacks = [];",
            "const flush_callbacks = [];",
            "const resolved_promise = Promise.resolve();",
            "let update_scheduled = false;"
        ):"";
        
        require(new String[]{
            "is_function",
            "flush",
            "add_render_callback",
            "mount_component",
            "run",
            "run_all",
            "get_current_component",
            "blank_object",
            "set_current_component"
        }, "svelte/internal", imports);
        
        
        
        
        ArrayList<String> headers = new ArrayList<>();
        
        imports.forEach(((path, importsObject) -> {
            importsObject.forEach((name, contents) -> {
                headers.add(contents);
            });
        }));
        
        String[] pieces = PATTERN_USE_STRICT.split(compiledContents, 2);
        compiledContents = String.join("\n", 
            pieces[0],
            "'use strict';",
            globals,
            String.join("\n",headers), 
            pieces[1]
        );
        return compiledContents;
    }
    
    private final ConcurrentHashMap<String,String> bundles = new ConcurrentHashMap<>();
    
    public String getInternal(){
        return internal;
    }
    private String resolveInternal() throws IOException{
        return Files
                .readString(Path.of("./node_modules/svelte/internal/index.js"))
                .replace("'use strict';", "")
                .replace("\"use strict\";", "")
                .replace("exports\\..*", "")
                .trim()
                ;
    }
    
    public String getBundle(String id){
        return bundles.get(id);
    }
    
    public void bundle(String id, String compiledSource){
        bundle(id, compiledSource, new HashMap<>());
    }
    
    private static final Pattern internalPattern = Pattern.compile(".*require\\s*\\(\\s*\"svelte\\/internal\"\\s*\\)\\s*;");
    
    public void bundle(String id, String compiledSource, HashMap<String,Object> props){
        String propsString = context.eval("js","(function (props){return JSON.stringify(props)})").execute(props).asString();
        //String[] pieces = internalPattern.split(compiledSource, 2);
        //compiledSource = pieces[0] + internal + pieces[1];

        compiledSource = 
        "(function () {\n" +
            compiledSource+
            "var app = new Component({\n" +
                "target: document.body,\n" +
                "props: "+propsString + "\n" +
            "});\n" +
            "return app;\n" +
        "}());";
        //System.out.println(compiledSource);
        bundles.put(id, compiledSource);
    }
    
    
}
