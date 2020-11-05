/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.github.tncrazvan.svelte3dom;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
                "const { compile } = require('./compiler.js');"
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
    
    private static final ProcessBuilder pb = new ProcessBuilder();
    public static String rollup(String filename, String charset) throws IOException{
        pb.directory(new File(System.getProperty("user.dir")));
        pb.command("node","rollup.js",filename);
        Process process = pb.start();
        InputStream is = process.getInputStream();
        String result = new String(is.readAllBytes(),charset);
        return result;
    }
    
    //compiling stuff
    
    private void require(String[] names, String path, LinkedHashMap<String,LinkedHashMap<String,String>> imports) throws IOException{
        path = path.trim();
        if(!imports.containsKey(path))
            imports.put(path, new LinkedHashMap<>());
        
        LinkedHashMap<String,String> importedPath = imports.get(path);
        
        for(String item : names){
            item = item.trim();
            if(path.endsWith(".svelte")){
                if(imports.get(path).containsKey(item)){
                    continue;
                }
                String value = 
                        PATTERN_USE_STRICT
                        .matcher(
                            compileFile(path,"UTF-8",imports,false,false)
                        )
                        .replaceAll("");
                
                value = value.replaceAll("export default Component;", "");
                importedPath.put(
                    item, 
                    "const "+item+" = (function (){\n" +
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
                }else {
                    //importedPath.put(item, Files.readString(Path.of(path)));
                    String value = Files.readString(Path.of(path));
                    value = PATTERN_EXPORT_DEFAULT.matcher(value).replaceFirst("return ");
                    value = 
                        "const "+item+" =(function (){\n" + 
                            value +
                        "})();"
                    ;
                    importedPath.put(item, value);
                }
            }
        }
    }
    
    
    private static final Pattern PATTERN_SVELTE_ITEMS = Pattern.compile("(?<=import).*(?=from)",Pattern.MULTILINE|Pattern.DOTALL);
    private static final Pattern PATTERN_SVELTE_PATH = Pattern.compile("(?<=from)\\s+[\"'].*[\"'];?",Pattern.MULTILINE|Pattern.DOTALL);
    private static final Pattern PATTERN_REQUIRES = Pattern.compile("import\\s\\{?\\s*\\n*([A-z_$][A-z0-9_$]*\\s*\\n*,*\\s*\\n*?)*\\s*\\n*\\}*\\s*\\n*from\\s*\\n*[\"'][A-z0-9_$\\/@\\.,;:|-]*[\"'\\s*\\n*];?",Pattern.MULTILINE|Pattern.DOTALL);
    private static final Pattern PATTERN_USE_STRICT = Pattern.compile("[\"']use strict[\"'];",Pattern.MULTILINE);
    private static final Pattern PATTERN_EXPORT_DEFAULT = Pattern.compile("export\\s+default\\s+",Pattern.MULTILINE);
    
    private void parseRequires(String requires, LinkedHashMap<String,LinkedHashMap<String,String>> imports) throws IOException{
        Matcher mpath = PATTERN_SVELTE_PATH.matcher(requires);
        if(!mpath.find())
            return;
        String path = mpath.group().replaceAll("('|;|\\\")+", "");
        Matcher mitems = PATTERN_SVELTE_ITEMS.matcher(requires);
        if(!mitems.find())
            return;
        String sitems = mitems
                .group()
                .replaceAll("(\\{|\\})+", "")
                .replaceAll(".*default:", "");
        String[] items = sitems.split(",");
        require(items, path, imports);
    }
    
    
    public static class ResolvedImport{
        String[] names;
        String path;
    }
    public String compileFile(String filename,String charset) throws IOException{
        return compileFile(filename, charset, new LinkedHashMap<>(), true, true);
    }
    public String compileFile(String filename,String charset, LinkedHashMap<String,LinkedHashMap<String,String>> imports) throws IOException{
        return compileFile(filename, charset, imports, true);
    }
    public String compileFile(String filename, String charset, LinkedHashMap<String,LinkedHashMap<String,String>> imports, boolean addGlobals) throws IOException{
        String contents = Files.readString(Path.of(filename), Charset.forName(charset));
        return compile(
            contents,
            imports,
            addGlobals,
            true
        );
    }
    public String compileFile(String filename, String charset, LinkedHashMap<String,LinkedHashMap<String,String>> imports, boolean addGlobals, boolean addImports) throws IOException{
        String contents = Files.readString(Path.of(filename), Charset.forName(charset));
        return compile(
            contents,
            imports,
            addGlobals,
            addImports
        );
    }
    
    public String compile(String source) throws IOException{
        return compile(source, new LinkedHashMap<>(), true, true);
    }
    
    public String compile(String source, LinkedHashMap<String,LinkedHashMap<String,String>> imports) throws IOException{
        return compile(source, imports, true, true);
    }
    
    public String compile(String source, LinkedHashMap<String,LinkedHashMap<String,String>> imports, boolean addGlobals) throws IOException{
        return compile(source, imports, addGlobals, true);
    }
    
    public String compile(String source, LinkedHashMap<String,LinkedHashMap<String,String>> imports, boolean addGlobals, boolean addImports) throws IOException{
        Value app = context.eval("js", "(function(source){return compile(source,{generate:'dom',format:'esm'}).js.code;});");
        String compiledContents = app.execute(source).asString();
        
        
        Matcher m = PATTERN_REQUIRES.matcher(compiledContents);
        while(m.find()){
            String item = m.group();
            //System.out.println("import:"+item);
            parseRequires(item.trim(),imports);
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
            "let update_scheduled = false;",
            "function make_dirty(component, i) {" +
                "if (component.$$.dirty[0] === -1) {" +
                    "dirty_components.push(component);" +
                    "schedule_update();" +
                    "component.$$.dirty.fill(0);" +
                "}" +
                "component.$$.dirty[(i / 31) | 0] |= (1 << (i % 31));" +
            "}",
            "function update($$) {" +
                "if ($$.fragment !== null) {" +
                    "$$.update();" +
                    "run_all($$.before_update);" +
                    "const dirty = $$.dirty;" +
                    "$$.dirty = [-1];" +
                    "$$.fragment && $$.fragment.p($$.ctx, dirty);" +
                    "$$.after_update.forEach(add_render_callback);" +
                "}" +
            "}"/*,
            "function dispatch(node, direction, kind) {" +
                "node.dispatchEvent(custom_event(`${direction ? 'intro' : 'outro'}${kind}`));" +
            "}"*/
        ):"";
        
        
        if(addImports)
            require(new String[]{
                "is_function",
                "flush",
                "add_render_callback",
                "mount_component",
                "run",
                "run_all",
                "get_current_component",
                "blank_object",
                "set_current_component",
                "schedule_update",
            }, "svelte/internal", imports);
        
        
        
        
        
        ArrayList<String> headers = new ArrayList<>();
        
        if(addImports){
            imports.forEach(((path, importsObject) -> {
                importsObject.forEach((name, contents) -> {
                    headers.add(contents);
                });
            }));
        }
        
        compiledContents = String.join("\n", 
            globals,
            String.join("\n",headers), 
            compiledContents
        );
        
        int end = compiledContents.length()-1-("export default Component;".length());
        compiledContents = compiledContents.substring(0, end);
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
    
    public String bundle(String compiledSource){
        return bundle(compiledSource, new LinkedHashMap<>());
    }
    
    private static final Pattern internalPattern = Pattern.compile(".*require\\s*\\(\\s*\"svelte\\/internal\"\\s*\\)\\s*;");
    
    public String bundle(String compiledSource, LinkedHashMap<String,Object> props){
        String propsString = context.eval("js","(function (props){return JSON.stringify(props)})").execute(props).asString();
        //String[] pieces = internalPattern.split(compiledSource, 2);
        //compiledSource = pieces[0] + internal + pieces[1];

        compiledSource = 
        "document.body.innerHTML = '';\n" +
        "(function () {\n" +
            compiledSource+
            "var app = new Component({\n" +
                "target: document.body,\n" +
                "props: "+propsString + "\n" +
            "});\n" +
            "return app;\n" +
        "}());";
        //System.out.println(compiledSource);
        //bundles.put(id, compiledSource);
        return compiledSource;
    }
    
    
}
