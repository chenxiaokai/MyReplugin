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

package com.qihoo360.replugin.gradle.host.creator.impl.json

import com.qihoo360.replugin.gradle.host.AppConstant
import com.qihoo360.replugin.gradle.host.creator.IFileCreator
import groovy.io.FileType
import groovy.json.JsonOutput

/**
 * @author RePlugin Team
 *
 * 生成 每个插件的 plugins-builtin.json 文件
 * E:\github\RePlugin-2.2.0\replugin-sample\host\app\build\intermediates\assets\baidu\release\plugins-builtin.json
 */
public class PluginBuiltinJsonCreator implements IFileCreator {

    def variant
    def config
    File fileDir
    def fileName
    def pluginInfos = []

    def PluginBuiltinJsonCreator(def project, def variant, def cfg) {
        this.config = cfg
        this.variant = variant
        //make sure processResources Task execute after mergeAssets Task
        String mergeAssetsTaskName = variant.getVariantData().getScope().getMergeAssetsTask().name
        //get real gradle task
        def mergeAssetsTask = project.tasks.getByName(mergeAssetsTaskName)
        //E:\github\RePlugin-2.2.0\replugin-sample\host\app\build\intermediates\assets\productFlavors\release
        fileDir = mergeAssetsTask.outputDir
        //config.builtInJsonFileName = plugins-builtin.json
        fileName = config.builtInJsonFileName
    }

    @Override
    String getFileName() {
        //config.builtInJsonFileName = plugins-builtin.json
        fileName
    }

    @Override
    File getFileDir() {
        ////E:\github\RePlugin-2.2.0\replugin-sample\host\app\build\intermediates\assets\productFlavors\release
        fileDir
    }

    @Override
    String getFileContent() {
        //查找插件文件并抽取信息,如果没有就直接返回null
        File pluginDirFile = new File(fileDir.getAbsolutePath() + File.separator + config.pluginDir)  //pluginDir = "plugins"
        if (!pluginDirFile.exists()) {
            println "${AppConstant.TAG} The ${pluginDirFile.absolutePath} does not exist "
            println "${AppConstant.TAG} pluginsInfo=null"
            return null
        }

        //遍历 E:\github\RePlugin-2.2.0\replugin-sample\host\app\build\intermediates\assets\productFlavors\release\plugins  下面的所有 jar 包
        new File(fileDir.getAbsolutePath() + File.separator + config.pluginDir)
                .traverse(type: FileType.FILES, nameFilter: ~/.*\${config.pluginFilePostfix}/) {   //pluginFilePostfix = ".jar"

            PluginInfoParser parser = null
            try {
                parser = new PluginInfoParser(it.absoluteFile, config)
            } catch (Exception e) {
                if (config.enablePluginFileIllegalStopBuild) {
                    System.err.println "${AppConstant.TAG} the plugin(${it.absoluteFile.absolutePath}) is illegal !!!"
                    throw new Exception(e)
                }
            }

            if (null != parser) {
                pluginInfos << parser.pluginInfo
            }
        }

        //插件为0个
        if (pluginInfos.isEmpty()) {
            println "${AppConstant.TAG} pluginsSize=0"
            println "${AppConstant.TAG} pluginsInfo=null"
            return null
        }

        //构建插件们的json信息
        def jsonOutput = new JsonOutput()
        String pluginInfosJson = jsonOutput.toJson(pluginInfos)
        //格式化打印插件们的json信息
        //< replugin-host-v2.2.0 > pluginsSize=2
        println "${AppConstant.TAG} pluginsSize=${pluginInfos.size()}"
        /*
         < replugin-host-v2.2.0 > pluginsInfo=[
         {
         "high": null,
         "frm": null,
         "ver": 104,
         "low": null,
         "pkg": "com.qihoo360.replugin.sample.demo1",
         "path": "plugins/demo1.jar",
         "name": "demo1"
         },
         {
         "high": null,
         "frm": null,
         "ver": 100,
         "low": null,
         "pkg": "com.qihoo360.replugin.sample.demo2",
         "path": "plugins/demo2.jar",
         "name": "demo2"
         }
         ]
         */
        println "${AppConstant.TAG} pluginsInfo=${jsonOutput.prettyPrint(pluginInfosJson)}"

        return pluginInfosJson
    }

}
