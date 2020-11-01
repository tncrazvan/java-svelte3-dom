/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.github.tncrazvan.svelte3dom;

import java.nio.file.Path;

/**
 *
 * @author Administrator
 */
public class Starter {
    public static void main(String[] args) {
        Svelte3DOM dom = new Svelte3DOM(Path.of(System.getProperty("user.dir")));
        System.out.println(dom.compile("<p>hello world</p>"));
    }
}
