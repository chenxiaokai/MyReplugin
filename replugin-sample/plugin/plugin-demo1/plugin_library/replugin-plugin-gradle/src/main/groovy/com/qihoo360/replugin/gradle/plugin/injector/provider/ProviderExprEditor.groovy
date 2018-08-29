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

import javassist.CannotCompileException
import javassist.expr.ExprEditor
import javassist.expr.MethodCall

/**
 * @author RePlugin Team
 */
public class ProviderExprEditor extends ExprEditor {

    static def PROVIDER_CLASS = 'com.qihoo360.replugin.loader.p.PluginProviderClient'

    public def filePath

    @Override
    void edit(MethodCall m) throws CannotCompileException {
        String clsName = m.getClassName()
        String methodName = m.getMethodName()

        if (clsName.equalsIgnoreCase('android.content.ContentResolver')) {
            if (!(methodName in ProviderInjector.includeMethodCall)) {
                // println "跳过$methodName"
                return
            }
            replaceStatement(m, methodName, m.lineNumber)
        }
    }

    def private replaceStatement(MethodCall methodCall, String method, def line) {
        if (methodCall.getMethodName() == 'registerContentObserver' || methodCall.getMethodName() == 'notifyChange') {

            //替换registerContentObserver或notifyChange :
            //replace statement：'{' + PROVIDER_CLASS + '.' + method + '(com.qihoo360.replugin.RePlugin.getPluginContext(), $$);}'，唯一特别的地方就是入参中传入了特定的context。

            methodCall.replace('{' + PROVIDER_CLASS + '.' + method + '(com.qihoo360.replugin.RePlugin.getPluginContext(), $$);}')
        } else {

            //替换query 等方法:
            //replace statement：'{$_ = ' + PROVIDER_CLASS + '.' + method + '(com.qihoo360.replugin.RePlugin.getPluginContext(), $$);}'，
            // 因为方法调用是有返回值的，所以statement必须将返回值赋值给特殊变量$_，这是javassist.expr.MethodCall方法的明确要求。

            methodCall.replace('{$_ = ' + PROVIDER_CLASS + '.' + method + '(com.qihoo360.replugin.RePlugin.getPluginContext(), $$);}')
        }

        //>>> Replace: E:\github\RePlugin-2.2.0\replugin-sample\plugin\plugin-demo1\app\build\intermediates\classes\release\com\qihoo360\replugin\sample\demo1\MainActivity$19.class Provider.insert():237
        println ">>> Replace: ${filePath} Provider.${method}():${line}"
    }
}
