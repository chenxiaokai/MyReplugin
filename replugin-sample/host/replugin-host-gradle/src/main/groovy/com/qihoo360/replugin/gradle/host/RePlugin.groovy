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

package com.qihoo360.replugin.gradle.host

import com.android.build.gradle.AppExtension
import com.android.build.gradle.AppPlugin
import com.qihoo360.replugin.gradle.host.creator.FileCreators
import com.qihoo360.replugin.gradle.host.creator.IFileCreator
import com.qihoo360.replugin.gradle.host.creator.impl.json.PluginBuiltinJsonCreator
import com.qihoo360.replugin.gradle.host.handlemanifest.ComponentsGenerator
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.logging.LogLevel
/**
 * @author RePlugin Team
 */
public class Replugin implements Plugin<Project> {

    def static TAG = AppConstant.TAG
    def project
    def config

    @Override
    public void apply(Project project) {
        println "${TAG} Welcome to replugin world ! "

        this.project = project

        /* Extensions */
        project.extensions.create(AppConstant.USER_CONFIG, RepluginConfig)

        //判断project中是否含有AppPlugin类型插件，即是否有'application' projects类型的Gradle plugin。我们在宿主项目中是应用了该类型插件的：apply plugin: 'com.android.application'.
        //如果希望判断是否有libraryPlugin,可以这样写：if (project.plugins.hasPlugin(LibraryPlugin))，it's for 'library' projects.
        if (project.plugins.hasPlugin(AppPlugin)) {

            //获取project中的AppExtension类型extension，即com.android.application projects的android extension.也就是在你的app模块的build.gradle中定义的闭包：
            //  android {
            //  ....
            // {
            def android = project.extensions.getByType(AppExtension)

            //遍历android extension的Application variants 列表。这里说下，这可以说是 Hook Android gradle 插件的一种方式，
            // 因为通过遍历applicationVariants，你可以修改属性，名字，描述，输出文件名等，如果是Android library库，那么就将applicationVariants替换为libraryVariants。
            android.applicationVariants.all { variant ->
                //variant.name 就是 debug 和 release
                addShowPluginTask(variant)

                if (config == null) {
                    config = project.extensions.getByName(AppConstant.USER_CONFIG)
                    checkUserConfig(config)
                }

                //applicationId 对应
                def appID = variant.generateBuildConfig.appPackageName

                //生成新的 manifest 文件
                def newManifest = ComponentsGenerator.generateComponent(appID, config)

                def variantData = variant.variantData
                def scope = variantData.scope

                //host generate task 生成HostConfig的task定义
                def generateHostConfigTaskName = scope.getTaskName(AppConstant.TASK_GENERATE, "HostConfig") //generateHostConfigTaskName 值为 rpGenerate{productFlavors}{buildTypes}HostConfig
                def generateHostConfigTask = project.task(generateHostConfigTaskName)  //创建task

                generateHostConfigTask.doLast {
                    //生成RepluginHostConfig配置文件的  .java文件
                    FileCreators.createHostConfig(project, variant, config)
                }
                generateHostConfigTask.group = AppConstant.TASKS_GROUP

                //http://blog.csdn.net/lzyzsd/article/details/46935405 Gradle中 task的依赖 和 finalizeBy 用处
                //depends on build config task
                String generateBuildConfigTaskName = variant.getVariantData().getScope().getGenerateBuildConfigTask().name  //生成BuildConfig.java的 task 名字
                def generateBuildConfigTask = project.tasks.getByName(generateBuildConfigTaskName)  ////生成BuildConfig.java的 task
                if (generateBuildConfigTask) {

                    //因为此task中创建的RePluginHostConfig.java希望放置到编译输出目录
                    // ..\replugin-sample\host\app\build\generated\source\buildConfig\{productFlavors}\{buildTypes}\...下，
                    // 所以此task依赖于生成 BuildConfig.java 的task并设置为 BuildConfigTask 执行完后，就执行HostConfigTask。

                    generateHostConfigTask.dependsOn generateBuildConfigTask
                    generateBuildConfigTask.finalizedBy generateHostConfigTask
                }

                //json generate task  生成插件apk 的json 文件信息
                def generateBuiltinJsonTaskName = scope.getTaskName(AppConstant.TASK_GENERATE, "BuiltinJson")  //生成task的名字 rpGenerate{productFlavors}{buildTypes}BuiltinJson
                def generateBuiltinJsonTask = project.task(generateBuiltinJsonTaskName)  //创建task 并增加到 project中

                generateBuiltinJsonTask.doLast {
                    ////指定了 generateBuiltinJsonTask 的task任务：扫描宿主\assets\plugins目录下的插件文件，并基于apk文件规则解析出插件信息，包名，版本号等，然后拼装成json文件
                    FileCreators.createBuiltinJson(project, variant, config)
                }
                generateBuiltinJsonTask.group = AppConstant.TASKS_GROUP

                //depends on mergeAssets Task
                String mergeAssetsTaskName = variant.getVariantData().getScope().getMergeAssetsTask().name
                def mergeAssetsTask = project.tasks.getByName(mergeAssetsTaskName)
                if (mergeAssetsTask) {
                    //因为此task中创建的 plugins-builtin.json 希望放置到编译
                    // 输出目录...\replugin-sample\host\app\build\intermediates\assets\{productFlavors}\{buildTypes}\...下，
                    // 所以此task依赖于merge assets文件 的task并设置为 mergeAssetsTask 执行完后，就执行BuiltinJsonTask。
                    generateBuiltinJsonTask.dependsOn mergeAssetsTask
                    mergeAssetsTask.finalizedBy generateBuiltinJsonTask
                }

                variant.outputs.each { output ->
                    //将坑位 xml 字符串 与 原有xml <application></application> 标签内的配置信息合二为一。
                    output.processManifest.doLast {
                        output.processManifest.outputs.files.each { File file ->
                            def manifestFile = null;
                            //在gradle plugin 3.0.0之前，file是文件，且文件名为AndroidManifest.xml
                            //在gradle plugin 3.0.0之后，file是目录，且不包含AndroidManifest.xml，需要自己拼接
                            //除了目录和AndroidManifest.xml之外，还可能会包含manifest-merger-debug-report.txt等不相干的文件，过滤它
                            if ((file.name.equalsIgnoreCase("AndroidManifest.xml") && !file.isDirectory()) || file.isDirectory()) {
                                if (file.isDirectory()) {
                                    //3.0.0之后，自己拼接AndroidManifest.xml
                                    manifestFile = new File(file, "AndroidManifest.xml")
                                } else {
                                    //3.0.0之前，直接使用
                                    manifestFile = file
                                }
                                //检测文件是否存在
                                if (manifestFile != null && manifestFile.exists()) {
                                    println "${AppConstant.TAG} handle manifest: ${manifestFile}"
                                    def updatedContent = manifestFile.getText("UTF-8").replaceAll("</application>", newManifest + "</application>")
                                    manifestFile.write(updatedContent, 'UTF-8')
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 添加 生成插件json信息的  任务
    def addShowPluginTask(def variant) {
        def variantData = variant.variantData
        def scope = variantData.scope

        // 得到这个 task 任务名 rpShowPlugins{productFlavors}{buildTypes}, scope.getTaskName返回是个String类型
        def showPluginsTaskName = scope.getTaskName(AppConstant.TASK_SHOW_PLUGIN, "")
        //创建一个task 名字 showPluginsTaskName 到 project中去
        def showPluginsTask = project.task(showPluginsTaskName)

        showPluginsTask.doLast {
            IFileCreator creator = new PluginBuiltinJsonCreator(project, variant, config)
            def dir = creator.getFileDir()

            if (!dir.exists()) {
                println "${AppConstant.TAG} The ${dir.absolutePath} does not exist "
                println "${AppConstant.TAG} pluginsInfo=null"
                return
            }

            String fileContent = creator.getFileContent()
            if (null == fileContent) {
                return
            }

            new File(dir, creator.getFileName()).write(fileContent, 'UTF-8')
        }
        showPluginsTask.group = AppConstant.TASKS_GROUP  //showPluginsTask 放入 replugin-plugin 任务组中

        //get mergeAssetsTask name   得到这个task名字 :app:merge{productFlavors}{buildTypes}Assets
        String mergeAssetsTaskName = variant.getVariantData().getScope().getMergeAssetsTask().name
        //get real gradle task
        def mergeAssetsTask = project.tasks.getByName(mergeAssetsTaskName)

        //depend on mergeAssetsTask so that assets have been merged
        if (mergeAssetsTask) {
            showPluginsTask.dependsOn mergeAssetsTask
        }

    }

    /**
     * 检查用户配置项
     */
    def checkUserConfig(config) {
/*
        def persistentName = config.persistentName

        if (persistentName == null || persistentName.trim().equals("")) {
            project.logger.log(LogLevel.ERROR, "\n---------------------------------------------------------------------------------")
            project.logger.log(LogLevel.ERROR, " ERROR: persistentName can'te be empty, please set persistentName in replugin. ")
            project.logger.log(LogLevel.ERROR, "---------------------------------------------------------------------------------\n")
            System.exit(0)
            return
        }
*/
        doCheckConfig("countProcess", config.countProcess)
        doCheckConfig("countTranslucentStandard", config.countTranslucentStandard)
        doCheckConfig("countTranslucentSingleTop", config.countTranslucentSingleTop)
        doCheckConfig("countTranslucentSingleTask", config.countTranslucentSingleTask)
        doCheckConfig("countTranslucentSingleInstance", config.countTranslucentSingleInstance)
        doCheckConfig("countNotTranslucentStandard", config.countNotTranslucentStandard)
        doCheckConfig("countNotTranslucentSingleTop", config.countNotTranslucentSingleTop)
        doCheckConfig("countNotTranslucentSingleTask", config.countNotTranslucentSingleTask)
        doCheckConfig("countNotTranslucentSingleInstance", config.countNotTranslucentSingleInstance)
        doCheckConfig("countTask", config.countTask)

        println '--------------------------------------------------------------------------'
//        println "${TAG} appID=${appID}"
        println "${TAG} useAppCompat=${config.useAppCompat}"
        // println "${TAG} persistentName=${config.persistentName}"
        println "${TAG} countProcess=${config.countProcess}"

        println "${TAG} countTranslucentStandard=${config.countTranslucentStandard}"
        println "${TAG} countTranslucentSingleTop=${config.countTranslucentSingleTop}"
        println "${TAG} countTranslucentSingleTask=${config.countTranslucentSingleTask}"
        println "${TAG} countTranslucentSingleInstance=${config.countTranslucentSingleInstance}"
        println "${TAG} countNotTranslucentStandard=${config.countNotTranslucentStandard}"
        println "${TAG} countNotTranslucentSingleTop=${config.countNotTranslucentSingleTop}"
        println "${TAG} countNotTranslucentSingleTask=${config.countNotTranslucentSingleTask}"
        println "${TAG} countNotTranslucentSingleInstance=${config.countNotTranslucentSingleInstance}"

        println "${TAG} countTask=${config.countTask}"
        println '--------------------------------------------------------------------------'
    }

    /**
     * 检查配置项是否正确
     * @param name 配置项
     * @param count 配置值
     */
    def doCheckConfig(def name, def count) {
        if (!(count instanceof Integer) || count < 0) {
            this.project.logger.log(LogLevel.ERROR, "\n--------------------------------------------------------")
            this.project.logger.log(LogLevel.ERROR, " ${TAG} ERROR: ${name} must be an positive integer. ")
            this.project.logger.log(LogLevel.ERROR, "--------------------------------------------------------\n")
            System.exit(0)
        }
    }
}

class RepluginConfig {

    /** 自定义进程的数量(除 UI 和 Persistent 进程) */
    def countProcess = 3

    /** 是否使用常驻进程？ */
    def persistentEnable = true

    /** 常驻进程名称（也就是上面说的 Persistent 进程，开发者可自定义）*/
    def persistentName = ':GuardService'

    /** 背景不透明的坑的数量 */
    def countNotTranslucentStandard = 6
    def countNotTranslucentSingleTop = 2
    def countNotTranslucentSingleTask = 3
    def countNotTranslucentSingleInstance = 2

    /** 背景透明的坑的数量 */
    def countTranslucentStandard = 2
    def countTranslucentSingleTop = 2
    def countTranslucentSingleTask = 2
    def countTranslucentSingleInstance = 3

    /** 宿主中声明的 TaskAffinity 的组数 */
    def countTask = 2

    /**
     * 是否使用 AppCompat 库
     * com.android.support:appcompat-v7:25.2.0
     */
    def useAppCompat = false

    /** HOST 向下兼容的插件版本 */
    def compatibleVersion = 10

    /** HOST 插件版本 */
    def currentVersion = 12

    /** plugins-builtin.json 文件名自定义,默认是 "plugins-builtin.json" */
    def builtInJsonFileName = "plugins-builtin.json"

    /** 是否自动管理 plugins-builtin.json 文件,默认自动管理 */
    def autoManageBuiltInJsonFile = true

    /** assert目录下放置插件文件的目录自定义,默认是 assert 的 "plugins" */
    def pluginDir = "plugins"

    /** 插件文件的后缀自定义,默认是".jar" 暂时支持 jar 格式*/
    def pluginFilePostfix = ".jar"

    /** 当发现插件目录下面有不合法的插件 jar (有可能是特殊定制 jar)时是否停止构建,默认是 true */
    def enablePluginFileIllegalStopBuild = true
}
