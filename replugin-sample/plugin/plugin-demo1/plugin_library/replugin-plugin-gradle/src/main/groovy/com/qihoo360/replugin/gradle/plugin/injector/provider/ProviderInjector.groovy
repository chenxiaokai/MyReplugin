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

package com.qihoo360.replugin.gradle.plugin.injector.provider

import com.qihoo360.replugin.gradle.plugin.inner.Util
import com.qihoo360.replugin.gradle.plugin.injector.BaseInjector
import javassist.ClassPool

import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes

/**
 * @author RePlugin Team
 */
public class ProviderInjector extends BaseInjector {

    // 需要处理的目标方法名
    public static def includeMethodCall = ['query',
                                           'getType',
                                           'insert',
                                           'bulkInsert',
                                           'delete',
                                           'update',
                                           'openInputStream',
                                           'openOutputStream',
                                           'openFileDescriptor',
                                           'registerContentObserver',
                                           'acquireContentProviderClient',
                                           'notifyChange',
    ]

    // 表达式编辑器
    def editor

    //ProviderInjector 替换 插件中的 ContentResolver 调用代码 为 插件库的PluginProviderClient中的对应方法调用
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
            editor = new ProviderExprEditor()
        }

        Util.newSection()
        //E:\github\RePlugin-2.2.0\replugin-sample\plugin\plugin-demo1\app\build\intermediates\exploded-aar\0848edb7126d45045f7ba46b11a297f3b693dd59\class
        //E:\github\RePlugin-2.2.0\replugin-sample\plugin\plugin-demo1\app\build\intermediates\exploded-aar\87c8f13e0cddc08265c43942441d25e39f1c0865\class
        //E:\github\RePlugin-2.2.0\replugin-sample\plugin\plugin-demo1\app\build\intermediates\classes\release
        println dir

        Files.walkFileTree(Paths.get(dir), new SimpleFileVisitor<Path>() {
            @Override
            FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {

                String filePath = file.toString()
                def stream, ctCls

                try {
                    if (filePath.contains('PluginProviderClient.class')) {
                        throw new Exception('can not replace self ')
                    }

                    stream = new FileInputStream(filePath)
                    ctCls = pool.makeClass(stream);

                    // println ctCls.name
                    if (ctCls.isFrozen()) {
                        ctCls.defrost()
                    }

                    editor.filePath = filePath
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
