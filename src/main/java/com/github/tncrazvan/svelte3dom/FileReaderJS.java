package com.github.tncrazvan.svelte3dom;


import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import org.graalvm.polyglot.Value;

/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

/**
 *
 * @author Administrator
 */
public class FileReaderJS {
    public static String readString(Value filename) throws IOException{
        return readString(filename, Value.asValue("UTF-8"));
    }
    public static String readString(Value filename, Value charset) throws IOException{
        String name = filename.asString();
        return Files.readString(Path.of(name), Charset.forName(charset.asString()));
    }
}
