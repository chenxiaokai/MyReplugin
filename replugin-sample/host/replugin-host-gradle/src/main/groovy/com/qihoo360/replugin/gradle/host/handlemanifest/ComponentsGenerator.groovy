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
 */

package com.qihoo360.replugin.gradle.host.handlemanifest

import groovy.xml.MarkupBuilder

/**
 * @author RePlugin Team
 */
class ComponentsGenerator {

    def static final infix = 'loader.a.Activity'

    def static final name = 'android:name'

    //此属性可以指定该组件应在哪个进程运行
    //设置了 android:process 属性将组件运行到另一个进程，相当于另一个应用程序，所以在另一个进程中也将新建一个 Application 的实例。因此，
    // 每新建一个进程 Application 的 onCreate 都将被调用一次。 如果在 Application 的 onCreate 中有许多初始化工作并且需要根据进程来区分的，那就需要特别注意了。
    def static final process = 'android:process'

    //http://blog.csdn.net/ljz2009y/article/details/26621815
    //这个 taskAffinity表示一个 启动一个Activity 应该放在哪个堆栈，
    //默认情况下，一个应用中的所有activity具有相同的taskAffinity，即应用程序的包名。我们可以通过设置不同的taskAffinity属性给应用中的activity分组，
    // 也可以把不同的应用中的activity的taskAffinity设置成相同的值
    def static final  task = 'android:taskAffinity'
    def static final launchMode = 'android:launchMode'
    def static final authorities = 'android:authorities'


    def static final multiprocess = 'android:multiprocess'

    def static final cfg = 'android:configChanges'
    def static final cfgV = 'keyboard|keyboardHidden|orientation|screenSize'

    def static final exp = 'android:exported'
    def static final expV = 'false'

    def static final ori = 'android:screenOrientation'
    def static final oriV = 'portrait'

    def static final theme = 'android:theme'
    def static final themeTS = '@android:style/Theme.Translucent.NoTitleBar'

    def static final THEME_NTS_USE_APP_COMPAT = '@style/Theme.AppCompat'
    def static final THEME_NTS_NOT_USE_APP_COMPAT = '@android:style/Theme.NoTitleBar'
    def static themeNTS = THEME_NTS_NOT_USE_APP_COMPAT

    /**
     * 动态生成插件化框架中需要的组件
     *
     * @param applicationID 宿主的 applicationID
     * @param config 用户配置
     * @return String       插件化框架中需要的组件
     */
    def static generateComponent(def applicationID, def config) {
        // 是否使用 AppCompat 库（涉及到默认主题）
        if (config.useAppCompat) {
            themeNTS = THEME_NTS_USE_APP_COMPAT
        } else {
            themeNTS = THEME_NTS_NOT_USE_APP_COMPAT
        }

        def writer = new StringWriter()
        //MarkupBuilder 生成 xml 文件
        def xml = new MarkupBuilder(writer)

        /* UI 进程 */
        xml.application {

            /* 需要编译期动态修改进程名的组件*/

            String pluginMgrProcessName = config.persistentEnable ? config.persistentName : applicationID

            // 常驻进程Provider
            /**
             * name = 'android:name'
             * authorities = 'android:authorities'

              总体来说它的主要作用是：是否支持其它应用调用当前组件。
              默认值：如果包含有intent-filter 默认值为true; 没有intent-filter默认值为false。
             * exp = 'android:exported'   // android:exported 详解 http://blog.csdn.net/watermusicyes/article/details/46460347
                   exported 大概意思是，是否支持其他应用调用当前组件
             * process = 'android:process'
             */
            provider(
                    "${name}":"com.qihoo360.replugin.component.process.ProcessPitProviderPersist",   //ProcessPitProviderPersist 在 replugin-host-lib工程中
                    "${authorities}":"${applicationID}.loader.p.main",
                    "${exp}":"false",
                    "${process}":"${pluginMgrProcessName}")

            provider(
                    "${name}":"com.qihoo360.replugin.component.provider.PluginPitProviderPersist",   //PluginPitProviderPersist 在 replugin-host-lib工程中
                    "${authorities}":"${applicationID}.Plugin.NP.PSP",
                    "${exp}":"false",
                    "${process}":"${pluginMgrProcessName}")

            // ServiceManager 服务框架
            //http://blog.csdn.net/qq_31097291/article/details/77962002
            // android:multiprocess="false"：provider会随着应用启动的时候加载，加载时provider是在应用默认主进程中初始化的。
            // 对于android:multiprocess=false（默认值），由系统把定义该ContentProvider的App启动起来(一个独立的Process)并实例化ContentProvider，
            // 这种ContentProvider只有一个实例，运行在自己App的Process中。所有调用者共享该ContentProvider实例，调用者与ContentProvider实例位于两个不同的Process。
            provider(
                    "${name}":"com.qihoo360.mobilesafe.svcmanager.ServiceProvider",  //ServiceProvider 在 replugin-host-lib工程中
                    "${authorities}":"${applicationID}.svcmanager",
                    "${exp}":"false",
                    "${multiprocess}":"false",
                    "${process}":"${pluginMgrProcessName}")

            service(
                    "${name}":"com.qihoo360.replugin.component.service.server.PluginPitServiceGuard",
                    "${process}":"${pluginMgrProcessName}")

            /* 透明背景坑的数量 并分别对应 activity 4 种启动状态*/
            /**
             * cfg = 'android:configChanges'  cfgV = 'keyboard|keyboardHidden|orientation|screenSize'
             * exp = 'android:exported'  expV = 'false'
             * ori = 'android:screenOrientation' oriV = 'portrait'
             * theme = 'android:theme' themeTS = '@android:style/Theme.Translucent.NoTitleBar'
             *
             * infix = 'loader.a.Activity'
             */
            config.countTranslucentStandard.times {
                activity(
                        "${name}": "${applicationID}.${infix}N1NRTS${it}",
                        "${cfg}": "${cfgV}",
                        "${exp}": "${expV}",
                        "${ori}": "${oriV}",
                        "${theme}": "${themeTS}")
            }
            config.countTranslucentSingleTop.times {
                activity(
                        "${name}": "${applicationID}.${infix}N1STPTS${it}",
                        "${cfg}": "${cfgV}",
                        "${exp}": "${expV}",
                        "${ori}": "${oriV}",
                        "${theme}": "${themeTS}",
                        "${launchMode}": "singleTop")
            }
            config.countTranslucentSingleTask.times {
                activity(
                        "${name}": "${applicationID}.${infix}N1STTS${it}",
                        "${cfg}": "${cfgV}",
                        "${exp}": "${expV}",
                        "${ori}": "${oriV}",
                        "${theme}": "${themeTS}",
                        "${launchMode}": "singleTask")
            }
            config.countTranslucentSingleInstance.times {
                activity(
                        "${name}": "${applicationID}.${infix}N1SITS${it}",
                        "${cfg}": "${cfgV}",
                        "${exp}": "${expV}",
                        "${ori}": "${oriV}",
                        "${theme}": "${themeTS}",
                        "${launchMode}": "singleInstance")
            }

            /* 背景不透明坑位 */
            config.countNotTranslucentStandard.times {
                activity(
                        "${name}": "${applicationID}.${infix}N1NRNTS${it}",
                        "${cfg}": "${cfgV}",
                        "${exp}": "${expV}",
                        "${ori}": "${oriV}",
                        "${theme}": "${themeNTS}")
            }
            config.countNotTranslucentSingleTop.times {
                activity(
                        "${name}": "${applicationID}.${infix}N1STPNTS${it}",
                        "${cfg}": "${cfgV}",
                        "${exp}": "${expV}",
                        "${ori}": "${oriV}",
                        "${theme}": "${themeNTS}",
                        "${launchMode}": "singleTop")
            }
            config.countNotTranslucentSingleTask.times {
                activity(
                        "${name}": "${applicationID}.${infix}N1STNTS${it}",
                        "${cfg}": "${cfgV}",
                        "${exp}": "${expV}",
                        "${ori}": "${oriV}",
                        "${theme}": "${themeNTS}",
                        "${launchMode}": "singleTask",)
            }
            config.countNotTranslucentSingleInstance.times {
                activity(
                        "${name}": "${applicationID}.${infix}N1SINTS${it}",
                        "${cfg}": "${cfgV}",
                        "${exp}": "${expV}",
                        "${ori}": "${oriV}",
                        "${theme}": "${themeNTS}",
                        "${launchMode}": "singleInstance")
            }

            /* TaskAffinity */
            // N1TA0NRTS1：UI进程->第0组->standardMode->透明主题->第1个坑位 (T: Task, NR: Standard, TS: Translucent)
            config.countTask.times { i ->
                config.countTranslucentStandard.times { j ->
                    activity(
                            "${name}": "${applicationID}.${infix}N1TA${i}NRTS${j}",
                            "${cfg}": "${cfgV}",
                            "${exp}": "${expV}",
                            "${ori}": "${oriV}",
                            "${theme}": "${themeTS}",
                            "${task}": ":t${i}")
                }
                config.countTranslucentSingleTop.times { j ->
                    activity(
                            "${name}": "${applicationID}.${infix}N1TA${i}STPTS${j}",
                            "${cfg}": "${cfgV}",
                            "${exp}": "${expV}",
                            "${ori}": "${oriV}",
                            "${theme}": "${themeTS}",
                            "${task}": ":t${i}",
                            "${launchMode}": "singleTop")
                }
                config.countTranslucentSingleTask.times { j ->
                    activity(
                            "${name}": "${applicationID}.${infix}N1TA${i}STTS${j}",
                            "${cfg}": "${cfgV}",
                            "${exp}": "${expV}",
                            "${ori}": "${oriV}",
                            "${theme}": "${themeTS}",
                            "${task}": ":t${i}",
                            "${launchMode}": "singleTask")
                }

                config.countNotTranslucentStandard.times { j ->
                    activity(
                            "${name}": "${applicationID}.${infix}N1TA${i}NRNTS${j}",
                            "${cfg}": "${cfgV}",
                            "${exp}": "${expV}",
                            "${ori}": "${oriV}",
                            "${theme}": "${themeNTS}",
                            "${task}": ":t${i}")
                }
                config.countNotTranslucentSingleTop.times { j ->
                    activity(
                            "${name}": "${applicationID}.${infix}N1TA${i}STPNTS${j}",
                            "${cfg}": "${cfgV}",
                            "${exp}": "${expV}",
                            "${ori}": "${oriV}",
                            "${theme}": "${themeNTS}",
                            "${task}": ":t${i}",
                            "${launchMode}": "singleTop")
                }
                config.countNotTranslucentSingleTask.times { j ->
                    activity(
                            "${name}": "${applicationID}.${infix}N1TA${i}STNTS${j}",
                            "${cfg}": "${cfgV}",
                            "${exp}": "${expV}",
                            "${ori}": "${oriV}",
                            "${theme}": "${themeNTS}",
                            "${task}": ":t${i}",
                            "${launchMode}": "singleTask")
                }
            }
        }
        // 删除 application 标签
        def normalStr = writer.toString().replace("<application>", "").replace("</application>", "")

        // 将单进程和多进程的组件相加
        normalStr + generateMultiProcessComponent(applicationID, config)
    }

    /**
     * 生成多进程坑位配置
     */
    def static generateMultiProcessComponent(def applicationID, def config) {
        if (config.countProcess == 0) {
            return ''
        }

        def writer = new StringWriter()
        def xml = new MarkupBuilder(writer)

        /* 自定义进程 */
        xml.application {
            config.countProcess.times { p ->
                config.countTranslucentStandard.times {
                    activity(
                            "${name}": "${applicationID}.${infix}P${p}NRTS${it}",
                            "${cfg}": "${cfgV}",
                            "${exp}": "${expV}",
                            "${ori}": "${oriV}",
                            "${theme}": "${themeTS}",
                            "${process}": ":p${p}")    // 以冒号开头为私有进程，以小写字母开头为公有进程
                }
                config.countTranslucentSingleTop.times {
                    activity(
                            "${name}": "${applicationID}.${infix}P${p}STPTS${it}",
                            "${cfg}": "${cfgV}",
                            "${exp}": "${expV}",
                            "${ori}": "${oriV}",
                            "${theme}": "${themeTS}",
                            "${process}": ":p${p}",
                            "${launchMode}": "singleTop")
                }
                config.countTranslucentSingleTask.times {
                    activity(
                            "${name}": "${applicationID}.${infix}P${p}STTS${it}",
                            "${cfg}": "${cfgV}",
                            "${exp}": "${expV}",
                            "${ori}": "${oriV}",
                            "${theme}": "${themeTS}",
                            "${process}": ":p${p}",
                            "${launchMode}": "singleTask")
                }
                config.countTranslucentSingleInstance.times {
                    activity(
                            "${name}": "${applicationID}.${infix}P${p}SITS${it}",
                            "${cfg}": "${cfgV}",
                            "${exp}": "${expV}",
                            "${ori}": "${oriV}",
                            "${theme}": "${themeTS}",
                            "${process}": ":p${p}",
                            "${launchMode}": "singleInstance")
                }
                config.countNotTranslucentStandard.times {
                    activity(
                            "${name}": "${applicationID}.${infix}P${p}NRNTS${it}",
                            "${cfg}": "${cfgV}",
                            "${exp}": "${expV}",
                            "${ori}": "${oriV}",
                            "${theme}": "${themeNTS}",
                            "${process}": ":p${p}")
                }
                config.countNotTranslucentSingleTop.times {
                    activity(
                            "${name}": "${applicationID}.${infix}P${p}STPNTS${it}",
                            "${cfg}": "${cfgV}",
                            "${exp}": "${expV}",
                            "${ori}": "${oriV}",
                            "${theme}": "${themeNTS}",
                            "${process}": ":p${p}",
                            "${launchMode}": "singleTop")
                }
                config.countNotTranslucentSingleTask.times {
                    activity(
                            "${name}": "${applicationID}.${infix}P${p}STNTS${it}",
                            "${cfg}": "${cfgV}",
                            "${exp}": "${expV}",
                            "${ori}": "${oriV}",
                            "${theme}": "${themeNTS}",
                            "${process}": ":p${p}",
                            "${launchMode}": "singleTask")
                }
                config.countNotTranslucentSingleInstance.times {
                    activity(
                            "${name}": "${applicationID}.${infix}P${p}SINTS${it}",
                            "${cfg}": "${cfgV}",
                            "${exp}": "${expV}",
                            "${ori}": "${oriV}",
                            "${theme}": "${themeNTS}",
                            "${process}": ":p${p}",
                            "${launchMode}": "singleInstance")
                }

                /* TaskAffinity */
                config.countTask.times { i ->
                    config.countTranslucentStandard.times { j ->
                        activity(
                                "${name}": "${applicationID}.${infix}P${p}TA${i}NRTS${j}",
                                "${cfg}": "${cfgV}",
                                "${exp}": "${expV}",
                                "${ori}": "${oriV}",
                                "${theme}": "${themeTS}",
                                "${process}": ":p${p}",
                                "${task}": ":t${i}")
                    }
                    config.countTranslucentSingleTop.times { j ->
                        activity(
                                "${name}": "${applicationID}.${infix}P${p}TA${i}STPTS${j}",
                                "${cfg}": "${cfgV}",
                                "${exp}": "${expV}",
                                "${ori}": "${oriV}",
                                "${theme}": "${themeTS}",
                                "${launchMode}": "singleTop",
                                "${process}": ":p${p}",
                                "${task}": ":t${i}")
                    }
                    config.countTranslucentSingleTask.times { j ->
                        activity(
                                "${name}": "${applicationID}.${infix}P${p}TA${i}STTS${j}",
                                "${cfg}": "${cfgV}",
                                "${exp}": "${expV}",
                                "${ori}": "${oriV}",
                                "${theme}": "${themeTS}",
                                "${launchMode}": "singleTask",
                                "${process}": ":p${p}",
                                "${task}": ":t${i}")
                    }
                    config.countNotTranslucentStandard.times { j ->
                        activity(
                                "${name}": "${applicationID}.${infix}P${p}TA${i}NRNTS${j}",
                                "${cfg}": "${cfgV}",
                                "${exp}": "${expV}",
                                "${ori}": "${oriV}",
                                "${theme}": "${themeNTS}",
                                "${process}": ":p${p}",
                                "${task}": ":t${i}")
                    }
                    config.countNotTranslucentSingleTop.times { j ->
                        activity(
                                "${name}": "${applicationID}.${infix}P${p}TA${i}STPNTS${j}",
                                "${cfg}": "${cfgV}",
                                "${exp}": "${expV}",
                                "${ori}": "${oriV}",
                                "${theme}": "${themeNTS}",
                                "${launchMode}": "singleTop",
                                "${process}": ":p${p}",
                                "${task}": ":t${i}")
                    }
                    config.countNotTranslucentSingleTask.times { j ->
                        activity(
                                "${name}": "${applicationID}.${infix}P${p}TA${i}STNTS${j}",
                                "${cfg}": "${cfgV}",
                                "${exp}": "${expV}",
                                "${ori}": "${oriV}",
                                "${theme}": "${themeNTS}",
                                "${launchMode}": "singleTask",
                                "${process}": ":p${p}",
                                "${task}": ":t${i}")
                    }
                }

                /* Provider */
                // 支持插件中的 Provider 调用
                provider("${name}": "com.qihoo360.replugin.component.provider.PluginPitProviderP${p}",   //PluginPitProviderP0, PluginPitProviderP1, PluginPitProviderP2 在 replugin-host-lib 工程中
                        "android:authorities": "${applicationID}.Plugin.NP.${p}",
                        "${process}": ":p${p}",
                        "${exp}": "${expV}")

                // fixme hujunjie 100 不写死
                // 支持进程Provider拉起
                provider("${name}": "com.qihoo360.replugin.component.process.ProcessPitProviderP${p}",  //ProcessPitProviderP0, ProcessPitProviderP1, ProcessPitProviderP2 在 replugin-host-lib 工程中
                        "android:authorities": "${applicationID}.loader.p.mainN${100 - p}",
                        "${process}": ":p${p}",
                        "${exp}": "${expV}")

                /* Service */
                // 支持使用插件的Service坑位
                // Added by Jiongxuan Zhang
                service("${name}": "com.qihoo360.replugin.component.service.server.PluginPitServiceP${p}",  //Manifest 增肌坑位
                        "${process}": ":p${p}",
                        "${exp}": "${expV}")
            }
        }

        // 删除 application 标签
        return writer.toString().replace("<application>", "").replace("</application>", "")
    }
}
