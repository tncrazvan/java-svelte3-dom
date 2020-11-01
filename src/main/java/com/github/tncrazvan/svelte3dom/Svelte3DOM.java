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
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
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
                    "const { component } = require('./compile.js');"
                    ;
            
            context.eval("js",tools);
            
            Value ssr = context.eval("js", "(function(service){dom=service;});");
            ssr.executeVoid(this);
            
            context.eval("js","console.log('JavaScript initialized.');");
            internal = resolveInternal();
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }
    
    public Context getContext(){
        return context;
    }
    
    public String compileFile(String filename,String charset) throws IOException{
        return compile(Files.readString(Path.of(filename), Charset.forName(charset)));
    }
    
    public String compile(String source){
        Value app = context.eval("js", "(function(source){return compile(source,{generate:'dom',format:'cjs'}).js.code;});");
        String result = app.execute(source).asString();
        return result;
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
        String[] pieces = internalPattern.split(compiledSource, 2);
        compiledSource = pieces[0] + internal + pieces[1];
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
