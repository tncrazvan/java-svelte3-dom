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
import java.util.List;
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
    
    private String require(String[] names, String path,HashMap<String,List<String>> imports){
        boolean pathFound = imports.containsKey(path);
        if(!pathFound)
            imports.put(path, new ArrayList<>());
        String result = "";
        
        for(String item : names){
            item = item.trim();
            List<String> importObject = imports.get(path);
            if(importObject.contains(item))
                continue;
            importObject.add(item);
            if(path.endsWith(".svelte")){
                System.out.println("################");
                System.out.println("compiler svelte:\n"+compile(path));
                System.out.println("____________________");
            }else{
                String script = "(function (){const {"+item+"} = require('"+path+"'); return "+item+".toString();})";
                result += context.eval("js",script).execute().asString()+"\n";
            }
        }
        return result;
    }
    
    
    private static final Pattern PATTERN_SVELTE_ITEMS = Pattern.compile("(?<=\\{).*(?=\\})",Pattern.MULTILINE|Pattern.DOTALL);
    private static final Pattern PATTERN_SVELTE_PATH = Pattern.compile("(?<=require\\(\\\").*(?=\\\"\\);)",Pattern.MULTILINE|Pattern.DOTALL);
    private static final Pattern PATTERN_SVELTE_ANYTHING = Pattern.compile("^const .*require\\([\"'].*[\"']\\);",Pattern.MULTILINE);
    
    private String[] matchExtractAndReplaceSvelteAnything(String compiledContents,HashMap<String,List<String>> imports){
        //FIRST TIME (matching & extracting)
        String svelteAnythingPieces[] = PATTERN_SVELTE_ANYTHING.split(compiledContents);
        Matcher m = PATTERN_SVELTE_ANYTHING.matcher(compiledContents);
        String[] replacements = new String[svelteAnythingPieces.length-1];
        int i = 0;
        while(m.find()){
            replacements[i] = extractRequires(m.group(),imports);
            i++;
        }
        
        //SECOND TIME (appending missing requirements)
        m = PATTERN_SVELTE_ANYTHING.matcher(compiledContents);
        for(i = 0; i < svelteAnythingPieces.length; i++){
            if(i<svelteAnythingPieces.length-1){
                svelteAnythingPieces[i] += replacements[i];
            }
        }
        
        return svelteAnythingPieces;
    }
    
    
    
    private String extractRequires(String requires,HashMap<String,List<String>> imports){
        Matcher mpath = PATTERN_SVELTE_PATH.matcher(requires);
        if(!mpath.find())
            return "";
        String path = mpath.group();
        Matcher mitems = PATTERN_SVELTE_ITEMS.matcher(requires);
        if(!mitems.find())
            return "";
        String[] items = mitems.group().split(",");
        return require(items, path, imports);
    }
    
    
    public static class ResolvedImport{
        String[] names;
        String path;
    }
    
    public String compileFile(String filename,String charset) throws IOException{
        return compile(Files.readString(Path.of(filename), Charset.forName(charset)));
    }
    
    public String compile(String source){
        return compile(source, new HashMap<>(), true);
    }
    
    public String compile(String source, HashMap<String,List<String>> imports){
        return compile(source, imports, true);
    }
    
    public String compile(String source, HashMap<String,List<String>> imports, boolean addGlobals){
        Value app = context.eval("js", "(function(source){return compile(source,{generate:'dom',format:'cjs'}).js.code;});");
        String compiledContents = app.execute(source).asString();
        
        String[] svelteAnythingPieces = matchExtractAndReplaceSvelteAnything(compiledContents,imports);
        
        
        String globals = addGlobals?String.join("\n", 
            "let exports = {}",
            "let flushing = false;",
            "const seen_callbacks = new Set();",
            "const dirty_components = [];",
            "const intros = { enabled: false };",
            "const binding_callbacks = [];",
            "const render_callbacks = [];",
            "const flush_callbacks = [];",
            "const resolved_promise = Promise.resolve();"
        ):"";
        
        compiledContents = String.join("\n",
            globals,
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
            }, "svelte/internal", imports),
            String.join("\n", svelteAnythingPieces)
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
        compiledSource = compiledSource
                .replace("'use strict';", "var app = (function () {let exports={};")
                .replace("\"use strict\";", "var app = (function () {let exports={};")
                .replace(
                        "exports.default = Component;",
                        
                            "var app = new Component({"
                                + "target: document.body,"
                                + "props: "+propsString
                            + "});\n" +
                            "return app;\n" +
                        "}());"
                )
                ;
        //System.out.println(compiledSource);
        bundles.put(id, compiledSource);
    }
    
    
}
