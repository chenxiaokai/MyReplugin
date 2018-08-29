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

import javassist.CannotCompileException
import javassist.expr.ExprEditor
import javassist.expr.MethodCall

/**
 * @author RePlugin Team
 */
public class LocalBroadcastExprEditor extends ExprEditor {

    //TARGET_CLASS和PROXY_CLASS分别指定了需要处理的目标类和对应的代理类
    static def TARGET_CLASS = 'android.support.v4.content.LocalBroadcastManager'
    static def PROXY_CLASS = 'com.qihoo360.replugin.loader.b.PluginLocalBroadcastManager'

    /** 处理以下方法 */
    //定义了需要处理的目标方法名
    static def includeMethodCall = ['getInstance',
                                    'registerReceiver',
                                    'unregisterReceiver',
                                    'sendBroadcast',
                                    'sendBroadcastSync']

    /** 待处理文件的物理路径 */
    public def filePath

    @Override
    void edit(MethodCall call) throws CannotCompileException {
        if (call.getClassName().equalsIgnoreCase(TARGET_CLASS)) {
            if (!(call.getMethodName() in includeMethodCall)) {
                // println "Skip $methodName"
                return
            }

            replaceStatement(call)
        }
    }

    def private replaceStatement(MethodCall call) {
        String method = call.getMethodName()
        if (method == 'getInstance') {
            //1）调用原型：PluginLocalBroadcastManager.getInstance(context);
            //2）replace statement：'{$_ = ' + PROXY_CLASS + '.' + method + '($$);}'，$$表示全部参数的简写。$_表示resulting value即返回值。
            call.replace('{$_ = ' + PROXY_CLASS + '.' + method + '($$);}')
        } else {
            def returnType = call.method.returnType.getName()
            // getInstance 之外的调用，要增加一个参数，请参看 i-library 的 LocalBroadcastClient.java
            if (returnType == 'void') {

                //替换registerReceiver unregisterReceiver sendBroadcastSync  这个三个api 返回 void
                //1）调用原型：PluginLocalBroadcastManager.registerReceiver(instance, receiver, filter);

                //2）replace statement：'{' + PROXY_CLASS + '.' + method + '($0, $$);}'，$0在这里就不代表this了，而是表示方法的调用方（参见：javassist tutorial），
                // 即PluginLocalBroadcastManager。因为调用原型中需要入参instance（要求是PluginLocalBroadcastManager类型），所以这里必须传入$0。

                //注：unregisterReceiver和sendBroadcastSync同上，调用原型请参见replugin-plugin-lib插件库中的PluginLocalBroadcastManager.java文件。
                call.replace('{' + PROXY_CLASS + '.' + method + '($0, $$);}')
            } else {
                //替换sendBroadcast （returnType != 'void'）:
                //1）调用原型：PluginLocalBroadcastManager.sendBroadcast(instance, intent);
                //replace statement：'{$_ = ' + PROXY_CLASS + '.' + method + '($0, $$);}'，传入调用方，全部参数，以及把返回值赋给特殊变量$_。
                call.replace('{$_ = ' + PROXY_CLASS + '.' + method + '($0, $$);}')
            }
        }

        println ">>> Replace: ${filePath} <line:${call.lineNumber}> ${TARGET_CLASS}.${method}() <With> ${PROXY_CLASS}.${method}()\n"
    }
}
