/*
 * Copyright (C) 2005-2017 Qihoo 360 Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed To in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 *
 */

package com.qihoo360.replugin.gradle.plugin.injector.localbroadcast

import com.qihoo360.replugin.gradle.plugin.injector.BaseInjector
import com.qihoo360.replugin.gradle.plugin.inner.Util
import javassist.ClassPool

import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes

/**
 * LocalBroadcastInjector
 *
 * 将插件中的 LocalBroadcast 调用转发到宿主
 *
 * @author RePlugin Team
 */
public class LocalBroadcastInjector extends BaseInjector {

    // 表达式编辑器
    def editor

    //LocalBroadcastInjector 替换插件中的LocalBroadcastManager调用代码 为 插件库的调用代码。
    @Override
    def injectClass(ClassPool pool, String dir, Map config) {

        // 不处理非 build 目录下的类
/*
        if (!dir.contains('build' + File.separator + 'intermediates')) {
            println "跳过$dir"
            return
        }
*/

        if (editor == null) {
            editor = new LocalBroadcastExprEditor()
        }

        Util.newSection()
        //E:\github\RePlugin-2.2.0\replugin-sample\plugin\plugin-demo1\app\build\intermediates\exploded-aar\0848edb7126d45045f7ba46b11a297f3b693dd59\class
        //E:\github\RePlugin-2.2.0\replugin-sample\plugin\plugin-demo1\app\build\intermediates\exploded-aar\87c8f13e0cddc08265c43942441d25e39f1c0865\class
        //E:\github\RePlugin-2.2.0\replugin-sample\plugin\plugin-demo1\app\build\intermediates\classes\release
        println dir

        //表示从Paths.get(dir)代表的节点开始遍历文件系统
        Files.walkFileTree(Paths.get(dir), new SimpleFileVisitor<Path>() {
            @Override
            FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {

                String filePath = file.toString()
                editor.filePath = filePath

                def stream, ctCls
                try {
                    // 不处理 LocalBroadcastManager.class
                    //保护性逻辑，避免替换掉v4包中的源码实现
                    if (filePath.contains('android/support/v4/content/LocalBroadcastManager')) {
                        println "Ignore ${filePath}"
                        return super.visitFile(file, attrs)
                    }

                    stream = new FileInputStream(filePath)
                    ctCls = pool.makeClass(stream);

                    // println ctCls.name
                    if (ctCls.isFrozen()) {
                        ctCls.defrost()
                    }

                    /* 检查方法列表 */
                    ctCls.getDeclaredMethods().each {
                        it.instrument(editor)
                    }

                    ctCls.getMethods().each {
                        it.instrument(editor)
                    }

                    ctCls.writeFile(dir)
                } catch (Throwable t) {
                    println "    [Warning] --> ${t.toString()}"
                    // t.printStackTrace()
                } finally {
                    if (ctCls != null) {
                        ctCls.detach()
                    }
                    if (stream != null) {
                        stream.close()
                    }
                }

                return super.visitFile(file, attrs)
            }
        })
    }
}
