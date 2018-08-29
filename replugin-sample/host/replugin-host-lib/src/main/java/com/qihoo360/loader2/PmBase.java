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

package com.qihoo360.loader2;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ProviderInfo;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import android.os.RemoteException;
import android.support.v4.content.LocalBroadcastManager;
import android.text.TextUtils;

import com.qihoo360.i.Factory;
import com.qihoo360.i.IModule;
import com.qihoo360.i.IPluginManager;
import com.qihoo360.mobilesafe.api.Tasks;
import com.qihoo360.replugin.IHostBinderFetcher;
import com.qihoo360.replugin.RePlugin;
import com.qihoo360.replugin.RePluginConstants;
import com.qihoo360.replugin.RePluginInternal;
import com.qihoo360.replugin.base.IPC;
import com.qihoo360.replugin.component.activity.DynamicClassProxyActivity;
import com.qihoo360.replugin.component.dummy.DummyActivity;
import com.qihoo360.replugin.component.dummy.DummyProvider;
import com.qihoo360.replugin.component.dummy.DummyService;
import com.qihoo360.replugin.component.process.PluginProcessHost;
import com.qihoo360.replugin.component.service.server.PluginPitService;
import com.qihoo360.replugin.helper.HostConfigHelper;
import com.qihoo360.replugin.helper.LogDebug;
import com.qihoo360.replugin.helper.LogRelease;
import com.qihoo360.replugin.model.PluginInfo;
import com.qihoo360.replugin.packages.PluginManagerProxy;
import com.qihoo360.replugin.utils.ReflectUtils;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.qihoo360.replugin.helper.LogDebug.LOG;
import static com.qihoo360.replugin.helper.LogDebug.PLUGIN_TAG;
import static com.qihoo360.replugin.helper.LogRelease.LOGR;
import static com.qihoo360.replugin.packages.PluginInfoUpdater.ACTION_UNINSTALL_PLUGIN;

/**
 * @author RePlugin Team
 */
class PmBase {

    private static final String TAG = "PmBase";

    static final String ACTION_NEW_PLUGIN = "ACTION_NEW_PLUGIN";

    static final String CONTAINER_SERVICE_PART = ".loader.s.Service";

    private static final String CONTAINER_PROVIDER_PART = ".loader.p.Provider";

    /**
     *
     */
    private final Context mContext;

    /**
     *  用来储存Activity
     */
    private final HashSet<String> mContainerActivities = new HashSet<String>();

    /**
     *  用来储存Provider
     */
    private final HashSet<String> mContainerProviders = new HashSet<String>();

    /**
     *  用来储存Service
     */
    private final HashSet<String> mContainerServices = new HashSet<String>();

    /**
     *
     */
    private final HashMap<String, HashMap<String, IModule>> mBuiltinModules = new HashMap<String, HashMap<String, IModule>>();

    /**
     *
     */
    private ClassLoader mClassLoader;

    /**
     * 所有插件
     */
    private final Map<String, Plugin> mPlugins = new ConcurrentHashMap<>();

    /**
     * 仿插件对象，用来实现主程序提供binder给其他模块
     */
    private final HashMap<String, IHostBinderFetcher> mBuiltinPlugins = new HashMap<String, IHostBinderFetcher>();

    /**
     * 动态的类查找表
     */
    private final HashMap<String, DynamicClass> mDynamicClasses = new HashMap<String, DynamicClass>();

    /**
     *
     */
    private String mDefaultPluginName;

    /**
     *
     */
    private Plugin mDefaultPlugin;

    /**
     * TODO init
     */
    long mLocalCookie;

    /**
     * TODO init
     */
    private boolean mNeedRestart;

    /**
     *
     */
    Builder.PxAll mAll;

    /**
     *
     */
    private PmHostSvc mHostSvc;

    /**
     *
     */
    PluginProcessPer mClient;

    /**
     * 负责宿主与插件、插件间的互通，可通过插件的Factory直接调用，也可通过RePlugin来跳转
     */
    PluginCommImpl mLocal;

    /**
     *  类似Activity的接口实现，内部实现了真正startActivity的逻辑、还有插件Activity生命周期的接口。
     */
    PluginLibraryInternalProxy mInternal;

    /**
     * insertNewPlugin 时使用的线程锁
     */
    private static final byte[] LOCKER = new byte[0];

    /**
     * 类映射
     */
    private static class DynamicClass {

        String plugin;

        /** @deprecated */
        String classType; // activity, service, provider

        Class defClass;

        String className;
    }

    static final void cleanIntentPluginParams(Intent intent) {
        // 防止 intent 攻击
        try {
            intent.removeExtra(IPluginManager.KEY_COMPATIBLE);
            intent.removeExtra(IPluginManager.KEY_PLUGIN);
            intent.removeExtra(IPluginManager.KEY_ACTIVITY);
        } catch (Exception e) {
            // ignore
        }
    }

    PmBase(Context context) {
        //
        mContext = context;

        // TODO init
        //init(context, this);

        //填充provider和services坑位名
        if (PluginManager.sPluginProcessIndex == IPluginManager.PROCESS_UI || PluginManager.isPluginProcess()) {
            String suffix;
            //如果是ui进程,设置suffix = N1;
            if (PluginManager.sPluginProcessIndex == IPluginManager.PROCESS_UI) {
                suffix = "N1";
            } else {
                suffix = "" + PluginManager.sPluginProcessIndex;
            }
            //结果 com.qihoo360.replugin.sample.host.loader.p.ProviderN1 这个在Manifest中 生成的坑位
            //或者 com.qihoo360.replugin.sample.host.loader.p.Provider{suffix}  这个在Manifest中 生成的坑位
            mContainerProviders.add(IPC.getPackageName() + CONTAINER_PROVIDER_PART + suffix);
            // 结果 com.qihoo360.replugin.sample.host.loader.s.ServiceN1  这个在Manifest中 生成的坑位
            // 或者 com.qihoo360.replugin.sample.host.loader.s.Service{suffix}  这个在Manifest中 生成的坑位
            mContainerServices.add(IPC.getPackageName() + CONTAINER_SERVICE_PART + suffix);
        }

        //IBinder 那个插件的进程通信类，包含PluginContainers，插件容器管理类的初始化
        // mClient 代表了当前Clent进程,也可以简单的想象成是插件，但并不全是插件
        mClient = new PluginProcessPer(context, this, PluginManager.sPluginProcessIndex, mContainerActivities);

        //本地插件管理器
        //负责宿主与插件、插件间的互通，可通过插件的Factory直接调用，也可通过RePlugin来跳转,创建的时候只是引用了Application和PmBase
        mLocal = new PluginCommImpl(context, this);

        // 插件内部activity管理器
        // Replugin框架中内部逻辑使用的很多方法都在这里，包括插件中通过“反射”调用的内部逻辑
        mInternal = new PluginLibraryInternalProxy(this);
    }

    void init() {
        //创建一个进程 Application 方法会运行两遍 一次是原来App进程，另一个是新开的远程进程，所以这里区分那个是持久进程那个是UI进程来分别做相应逻辑
        if (HostConfigHelper.PERSISTENT_ENABLE) {
            // （默认）“常驻进程”作为插件管理进程，则常驻进程作为Server，其余进程作为Client
            if (IPC.isPersistentProcess()) {
                // 初始化“Server”所做工作
                initForServer();
            } else {
                // 连接到Server
                initForClient();
            }
        } else {
            // “UI进程”作为插件管理进程（唯一进程），则UI进程既可以作为Server也可以作为Client
            if (IPC.isUIProcess()) {
                // 1. 尝试初始化Server所做工作，
                initForServer();

                // 2. 注册该进程信息到“插件管理进程”中
                // 注意：这里无需再做 initForClient，因为不需要再走一次Binder
                PMF.sPluginMgr.attach();

            } else {
                // 其它进程？直接连接到Server即可
                initForClient();
            }
        }

        // 最新快照
        PluginTable.initPlugins(mPlugins);

        // 输出
        if (LOG) {
            for (Plugin p : mPlugins.values()) {
                // plugin: p=PInfo { <demo3:100(4)> [APK] [DEX_EXTRACTED] [USED] js={"pkgname":"com.qihoo360.replugin.sample.demo3","ali":"demo3","name":"demo3","low":10,"high":10,"ver":100,"verv":2814792716779620,"path":"\/data\/user\/0\/com.qihoo360.replugin.sample.host\/app_p_a\/-334910795.jar","type":11,"frm_ver":4,"used":true} dex=/data/user/0/com.qihoo360.replugin.sample.host/app_p_od/-334910795.dex nlib=/data/user/0/com.qihoo360.replugin.sample.host/app_p_n/-334910795 }
                // plugin: p=PInfo { <demo3:100(4)> [APK] [DEX_EXTRACTED] [USED] js={"pkgname":"com.qihoo360.replugin.sample.demo3","ali":"demo3","name":"demo3","low":10,"high":10,"ver":100,"verv":2814792716779620,"path":"\/data\/user\/0\/com.qihoo360.replugin.sample.host\/app_p_a\/-334910795.jar","type":11,"frm_ver":4,"used":true} dex=/data/user/0/com.qihoo360.replugin.sample.host/app_p_od/-334910795.dex nlib=/data/user/0/com.qihoo360.replugin.sample.host/app_p_n/-334910795 }
                // plugin: p=PInfo { <demo2:100(4)> [BUILTIN] js={"pkgname":"com.qihoo360.replugin.sample.demo2","ali":"demo2","name":"demo2","low":10,"high":10,"ver":100,"verv":2814792716779620,"path":"plugins\/demo2.jar","type":2,"frm_ver":4} dex=/data/user/0/com.qihoo360.replugin.sample.host/app_plugins_v3_odex/demo2-10-10-100.dex nlib=/data/user/0/com.qihoo360.replugin.sample.host/app_plugins_v3_libs/demo2-10-10-100 }
                // plugin: p=PInfo { <demo1:104(4)> [BUILTIN] [DEX_EXTRACTED] [USED] js={"pkgname":"com.qihoo360.replugin.sample.demo1","ali":"demo1","name":"demo1","low":10,"high":10,"ver":104,"verv":2814792716779624,"path":"plugins\/demo1.jar","type":2,"frm_ver":4} dex=/data/user/0/com.qihoo360.replugin.sample.host/app_plugins_v3_odex/demo1-10-10-104.dex nlib=/data/user/0/com.qihoo360.replugin.sample.host/app_plugins_v3_libs/demo1-10-10-104 }
                // plugin: p=PInfo { <demo1:104(4)> [BUILTIN] [DEX_EXTRACTED] [USED] js={"pkgname":"com.qihoo360.replugin.sample.demo1","ali":"demo1","name":"demo1","low":10,"high":10,"ver":104,"verv":2814792716779624,"path":"plugins\/demo1.jar","type":2,"frm_ver":4} dex=/data/user/0/com.qihoo360.replugin.sample.host/app_plugins_v3_odex/demo1-10-10-104.dex nlib=/data/user/0/com.qihoo360.replugin.sample.host/app_plugins_v3_libs/demo1-10-10-104 }
                // plugin: p=PInfo { <demo2:100(4)> [BUILTIN] js={"pkgname":"com.qihoo360.replugin.sample.demo2","ali":"demo2","name":"demo2","low":10,"high":10,"ver":100,"verv":2814792716779620,"path":"plugins\/demo2.jar","type":2,"frm_ver":4} dex=/data/user/0/com.qihoo360.replugin.sample.host/app_plugins_v3_odex/demo2-10-10-100.dex nlib=/data/user/0/com.qihoo360.replugin.sample.host/app_plugins_v3_libs/demo2-10-10-100 }
                LogDebug.d(PLUGIN_TAG, "plugin: p=" + p.mInfo);
            }
        }
    }

    /**
     * Persistent(常驻)进程的初始化
     *
     */
    private final void initForServer() {
        if (LOG) {
            LogDebug.d(PLUGIN_TAG, "search plugins from file system");
        }

        //PmHostSvc 运行在常驻进程里面
        mHostSvc = new PmHostSvc(mContext, this);
        //在Persistent进程中也会通过PluginProcessMain.installHost(mHostSvc)连接到IPluginManagerServer，但因为IPluginManagerServer就运行在当前进程，因此这里不会进行Binder通信，
        // 而是直接调用PmHostSvc端fetchManagerServer方法
        PluginProcessMain.installHost(mHostSvc);
        PluginProcessMain.schedulePluginProcessLoop(PluginProcessMain.CHECK_STAGE1_DELAY);

        // 兼容即将废弃的p-n方案 by Jiongxuan Zhang
        //这三行是为了兼容存在，以后会被废弃掉，所以不用太关注
        mAll = new Builder.PxAll();
        Builder.builder(mContext, mAll);
        refreshPluginMap(mAll.getPlugins());

        // [Newest!] 使用全新的RePlugin APK方案
        // Added by Jiongxuan Zhang
        try {
            //加载插件并保存起来，这样做是为了客户端进程才能获取到插件信息
            List<PluginInfo> l = PluginManagerProxy.load();
            if (l != null) {
                // 将"纯APK"插件信息并入总的插件信息表中，方便查询
                // 这里有可能会覆盖之前在p-n中加入的信息。本来我们就想这么干，以"纯APK"插件为准
                refreshPluginMap(l); //将获取的插件信息保存在 PmBase.mPlugins 中
            }
        } catch (RemoteException e) {
            if (LOGR) {
                LogRelease.e(PLUGIN_TAG, "lst.p: " + e.getMessage(), e);
            }
        }
    }

    /**
     * Client(UI进程)的初始化
     *
     */
    private final void initForClient() {
        if (LOG) {
            LogDebug.d(PLUGIN_TAG, "list plugins from persistent process");
        }

        // 1. 先尝试连接
        PluginProcessMain.connectToHostSvc();

        // 2. 然后从常驻进程获取插件列表
        refreshPluginsFromHostSvc();
    }

    /**
     * 从HostSvc（插件管理所在进程）获取所有的插件信息
     */
    private void refreshPluginsFromHostSvc() {
        List<PluginInfo> plugins = null;
        try {
            //此处getPluginHost() 返回的是 PmHostSvc 的对象 调用常驻进程来 获取插件
            //获取的插件是从 :GuridService 中获取，：GuridService先启动起来
            plugins = PluginProcessMain.getPluginHost().listPlugins();
        } catch (Throwable e) {
            if (LOGR) {
                LogRelease.e(PLUGIN_TAG, "lst.p: " + e.getMessage(), e);
            }
        }

        // 判断是否有需要更新的插件
        // FIXME 执行此操作前，判断下当前插件的运行进程，具体可以限制仅允许该插件运行在一个进程且为自身进程中
        List<PluginInfo> updatedPlugins = null;
        if (isNeedToUpdate(plugins)) {
            if (LOG) {
                LogDebug.d(PLUGIN_TAG, "plugins need to perform update operations");
            }
            try {
                updatedPlugins = PluginManagerProxy.updateAllPlugins();
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }

        if (updatedPlugins != null) {
            refreshPluginMap(updatedPlugins);
        } else {
            refreshPluginMap(plugins);
        }
    }

    /**
     * 判断列表中是否有需要更新的插件
     *
     * @param plugins 要检查的插件的列表
     * @return 是否有需要更新的插件
     */
    private final boolean isNeedToUpdate(List<PluginInfo> plugins) {
        if (plugins != null) {
            for (PluginInfo info : plugins) {
                if (info.getJSON().optJSONObject("upinfo") != null) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 更新所有的插件信息
     *
     * @param plugins
     */
    private final void refreshPluginMap(List<PluginInfo> plugins) {
        if (plugins == null) {
            return;
        }
        for (PluginInfo info : plugins) {
            Plugin plugin = Plugin.build(info);
            putPluginObject(info, plugin);
            LogDebug.d("chenxiaokai","PmBase  refreshPluginMap() "+info);
        }
    }

    private void putPluginObject(PluginInfo info, Plugin plugin) {
        // 同时加入PackageName和Alias（如有）
        mPlugins.put(info.getPackageName(), plugin);
        if (!TextUtils.isEmpty(info.getAlias())) {
            // 即便Alias和包名相同也可以再Put一次，反正只是覆盖了相同Value而已
            mPlugins.put(info.getAlias(), plugin);
        }
    }

    final void attach() {
        //
        try {
            mDefaultPluginName = PluginProcessMain.getPluginHost().attachPluginProcess(IPC.getCurrentProcessName(), PluginManager.sPluginProcessIndex, mClient, mDefaultPluginName);
        } catch (Throwable e) {
            if (LOGR) {
                LogRelease.e(PLUGIN_TAG, "c.n.a: " + e.getMessage(), e);
            }
        }
    }

    final void installBuiltinPlugin(String name, IHostBinderFetcher p) {
        synchronized (mBuiltinPlugins) {
            mBuiltinPlugins.put(name, p);
        }
    }

    final void callAttach() {
        // 获取RepluginClassLoader
        mClassLoader = PmBase.class.getClassLoader();

        // 挂载
        for (Plugin p : mPlugins.values()) {  //mPlugins所有插件  在 initForClient() 就已经加载到 mPlugins中
            p.attach(mContext, mClassLoader, mLocal);  //获取的插件与当前进程关联
        }

        // 加载默认插件
        if (PluginManager.isPluginProcess()) {  //如果插件启动了自己单独的进程，就会启动插件
            if (!TextUtils.isEmpty(mDefaultPluginName)) {

                LogDebug.d("chenxiaokai","PmBase callAttach() mDefaultPluginName = "+mDefaultPluginName);

                Plugin p = mPlugins.get(mDefaultPluginName);
                if (p != null) {
                    //加载插件
                    boolean rc = p.load(Plugin.LOAD_APP, true);
                    if (!rc) {
                        if (LOG) {
                            LogDebug.d(PLUGIN_TAG, "failed to load default plugin=" + mDefaultPluginName);
                        }
                    }
                    if (rc) {
                        mDefaultPlugin = p;
                        mClient.init(p);
                    }
                }
            }
        }
    }

    /**
     * @param name
     * @param modc
     * @param module
     */
    final void addBuiltinModule(String name, Class<? extends IModule> modc, IModule module) {
        HashMap<String, IModule> modules = mBuiltinModules.get(name);
        if (modules == null) {
            modules = new HashMap<String, IModule>();
            mBuiltinModules.put(name, modules);
        }
        modules.put(modc.getName(), module);
    }

    final boolean addDynamicClass(String className, String plugin, String type, String target, Class defClass) {
        if (LOG) {
            LogDebug.d(PLUGIN_TAG, "addDynamicClass: class=" + className + " plugin=" + plugin + " type=" + type + " target=" + target + " def=" + defClass);
        }
        if (mDynamicClasses.containsKey(className)) {
            return false;
        }
        DynamicClass dc = new DynamicClass();
        dc.plugin = plugin;
        dc.classType = type;
        dc.className = target;
        dc.defClass = defClass;
        mDynamicClasses.put(className, dc);
        return true;
    }

    /**
     * 检查插件的某个类是否是动态注册的
     *
     * @param plugin    插件名称
     * @param className 要动态注册的类
     * @return 插件的这个类是否是动态类
     */
    final boolean isDynamicClass(String plugin, String className) {
        if (!TextUtils.isEmpty(className) && !TextUtils.isEmpty(plugin)) {
            DynamicClass dc = mDynamicClasses.get(className);
            if (dc != null) {
                return plugin.equals(dc.plugin);
            }
        }
        return false;
    }

    final void removeDynamicClass(String className) {
        mDynamicClasses.remove(className);
    }

    /**
     * 返回 className 对应的 插件名称
     *
     * @param className 插件名称
     * @return 返回动态注册类对应的插件名称
     */
    final String getPluginByDynamicClass(String className) {
        DynamicClass dc = mDynamicClasses.get(className);
        if (dc != null) {
            return dc.plugin;
        }
        return "";
    }

    final void callAppCreate() {
        // 计算/获取cookie
        if (IPC.isPersistentProcess()) {
            mLocalCookie = PluginProcessMain.getPersistentCookie();
        } else {
//            try {
//                mLocalCookie = PmCore.fetchPersistentCookie();
//            } catch (RuntimeException e) {
//                //
//                LogDebug.i(PLUGIN_TAG, "catch exception: " + e.getMessage(), e);
//                //
//                String processName = mContext.getApplicationInfo().packageName + MobileSafeApplication.PERSIST_PROCESS_POSFIX;
//                int uid = mContext.getApplicationInfo().uid;
//                int flags = 0;
//                List<ProviderInfo> providers = mContext.getPackageManager().queryContentProviders(processName, uid, flags);
//                LogDebug.i(PLUGIN_TAG, "providers.size=" + (providers != null ? providers.size() : "null"));
//                if (providers != null) {
//                    for (ProviderInfo pi : providers) {
//                        LogDebug.i(PLUGIN_TAG, "name=" + pi.name + " auth=" + pi.authority);
//                    }
//                }
//                //
//                throw e;
//            }
        }
        if (LOG) {
            LogDebug.d(PLUGIN_TAG, "initial local cookie=" + mLocalCookie);
        }

//        // 退出监控
//        if (IPC.isPersistentProcess()) {
//            // 异步通知，否则可能发生如下错误:
//            //Attempt to invoke virtual method 'android.os.Looper android.content.Context.getMainLooper()' on a null object reference
//            //java.lang.NullPointerException: Attempt to invoke virtual method 'android.os.Looper android.content.Context.getMainLooper()' on a null object reference
//            //    at android.os.Parcel.readException(Parcel.java:1546)
//            //    at android.os.Parcel.readException(Parcel.java:1493)
//            //    at com.qihoo360.loader2.IPluginClient$Stub$Proxy.sendIntent(IPluginClient.java:165)
//            //    at com.qihoo360.loader2.PmCore.sendIntent2Process(PmCore.java:386)
//            //    at com.qihoo360.loader2.PluginManager.sendIntent2Process(PluginManager.java:1124)
//            //    at com.qihoo360.loader2.MP.sendLocalBroadcast2All(MP.java:83)
//            //    at com.qihoo360.mobilesafe.ui.index.IPC.sendLocalBroadcast2All(IPC.java:196)
//            //    at com.qihoo360.mobilesafe.api.IPC.sendLocalBroadcast2All(IPC.java:131)
//            Tasks.post2UI(new Runnable() {
//
//                @Override
//                public void run() {
//                    Intent intent = new Intent(ACTION_PERSISTENT_NEW_COOKIE);
//                    intent.putExtra(KEY_COOKIE, mLocalCookie);
//                    IPC.sendLocalBroadcast2All(mContext, intent);
//                }
//            });
//        }
//
//        if (sPluginProcessIndex >= 0 && sPluginProcessIndex < Constant.STUB_PROCESS_COUNT) {
//            IntentFilter filter = new IntentFilter(ACTION_PERSISTENT_NEW_COOKIE);
//            LocalBroadcastManager.getInstance(mContext).registerReceiver(new BroadcastReceiver() {
//
//                @Override
//                public void onReceive(Context context, Intent intent) {
//                    if (ACTION_PERSISTENT_NEW_COOKIE.equals(intent.getAction())) {
//                        long cookie = intent.getLongExtra(KEY_COOKIE, 0);
//                        if (LOG) {
//                            LogDebug.d(PLUGIN_TAG, "received cookie=" + cookie);
//                        }
//                        if (mLocalCookie != cookie) {
//                            if (LOG) {
//                                LogDebug.d(PLUGIN_TAG, "received new cookie=" + cookie + " old=" + mLocalCookie + " quit ...");
//                            }
//                            // 退出
//                            System.exit(0);
//                        }
//                    }
//                }
//            }, filter);
//        }

        if (!IPC.isPersistentProcess()) {
            // 由于常驻进程已经在内部做了相关的处理，此处仅需要在UI进程注册并更新即可
            registerReceiverAction(ACTION_NEW_PLUGIN);
        }
        // 由于常驻进程内未做处理，因此需要在常驻和UI进程同时注册并更新缓存信息
        registerReceiverAction(ACTION_UNINSTALL_PLUGIN);
    }

    /**
     * @param action
     */
    private final void registerReceiverAction(final String action) {
        IntentFilter filter = new IntentFilter(action);
        LocalBroadcastManager.getInstance(mContext).registerReceiver(new BroadcastReceiver() {

            @Override
            public void onReceive(Context context, Intent intent) {
                if (action.equals(intent.getAction())) {
                    PluginInfo info = intent.getParcelableExtra("obj");
                    if (info != null) {
                        switch (action) {
                            case ACTION_NEW_PLUGIN:
                                // 非常驻进程上下文
                                newPluginFound(info, intent.getBooleanExtra(RePluginConstants.KEY_PERSIST_NEED_RESTART, false));
                                break;
                            case ACTION_UNINSTALL_PLUGIN:
                                pluginUninstalled(info);
                                break;
                        }
                    }
                }
            }
        }, filter);
    }

    /**
     * @param className
     * @param resolve
     * @return
     */
    final Class<?> loadClass(String className, boolean resolve) {
        // 加载Service中介坑位
        if (className.startsWith(PluginPitService.class.getName())) {
            if (LOG) {
                LogDebug.i(TAG, "loadClass: Loading PitService Class... clz=" + className);
            }
            return PluginPitService.class;
        }

        //
        if (mContainerActivities.contains(className)) {
            //mClient是PluginProcessPer的实例，而PluginProcessPer是IPluginClient的实现类
            Class<?> c = mClient.resolveActivityClass(className);
            if (c != null) {
                return c;
            }
            // 输出warn日志便于查看
            // use DummyActivity orig=
            if (LOGR) {
                LogRelease.w(PLUGIN_TAG, "p m hlc u d a o " + className);
            }
            return DummyActivity.class;
        }

        //
        if (mContainerServices.contains(className)) {
            Class<?> c = loadServiceClass(className);
            if (c != null) {
                return c;
            }
            // 输出warn日志便于查看
            // use DummyService orig=
            if (LOGR) {
                LogRelease.w(PLUGIN_TAG, "p m hlc u d s o " + className);
            }
            return DummyService.class;
        }

        //
        if (mContainerProviders.contains(className)) {
            Class<?> c = loadProviderClass(className);
            if (c != null) {
                return c;
            }
            // 输出warn日志便于查看
            // use DummyProvider orig=
            if (LOGR) {
                LogRelease.w(PLUGIN_TAG, "p m hlc u d p o " + className);
            }
            return DummyProvider.class;
        }

        // 插件定制表
        DynamicClass dc = mDynamicClasses.get(className);
        if (dc != null) {
            final Context context = RePluginInternal.getAppContext();
            PluginDesc desc = PluginDesc.get(dc.plugin);

            if (LOG) {
                LogDebug.d("loadClass", "desc=" + desc);
                if (desc != null) {
                    LogDebug.d("loadClass", "desc.isLarge()=" + desc.isLarge());
                }
                LogDebug.d("loadClass", "RePlugin.isPluginDexExtracted(" + dc.plugin + ") = " + RePlugin.isPluginDexExtracted(dc.plugin));
            }

            // 加载动态类时，如果其对应的插件未下载，则转到代理类
            if (desc != null) {
                String plugin = desc.getPluginName();
                if (PluginTable.getPluginInfo(plugin) == null) {
                    if (LOG) {
                        LogDebug.d("loadClass", "plugin=" + plugin + " not found, return DynamicClassProxyActivity.class");
                    }
                    return DynamicClassProxyActivity.class;
                }
            }

            /* 加载未安装的大插件时，启动一个过度 Activity */
            // todo fixme 仅对 activity 类型才弹窗
            boolean needStartLoadingActivity = (desc != null && desc.isLarge() && !RePlugin.isPluginDexExtracted(dc.plugin));
            if (LOG) {
                LogDebug.d("loadClass", "needStartLoadingActivity = " + needStartLoadingActivity);
            }
            if (needStartLoadingActivity) {
                Intent intent = new Intent();
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                // fixme 将 PluginLoadingActivity2 移到 replugin 中来，不写死
                intent.setComponent(new ComponentName(IPC.getPackageName(), "com.qihoo360.loader2.updater.PluginLoadingActivity2"));
                context.startActivity(intent);
            }

            Plugin p = loadAppPlugin(dc.plugin);
            if (LOG) {
                LogDebug.d("loadClass", "p=" + p);
            }
            if (p != null) {
                try {
                    Class<?> cls = p.getClassLoader().loadClass(dc.className);
                    if (needStartLoadingActivity) {
                        // 发广播给过度 Activity，让其关闭
                        // fixme 发送给 UI 进程
                        Tasks.postDelayed2Thread(new Runnable() {
                            @Override
                            public void run() {
                                if (LOG) {
                                    LogDebug.d("loadClass", "发广播，让 PluginLoadingActivity2 消失");
                                }
                                IPC.sendLocalBroadcast2All(context, new Intent("com.qihoo360.replugin.load_large_plugin.dismiss_dlg"));
                            }
                        }, 300);
                        // IPC.sendLocalBroadcast2Process(context, IPC.getPersistentProcessName(), new Intent("com.qihoo360.replugin.load_large_plugin.dismiss_dlg"), )
                    }
                    return cls;
                } catch (Throwable e) {
                    if (LOGR) {
                        LogRelease.w(PLUGIN_TAG, "p m hlc dc " + className, e);
                    }
                }
            } else {
                if (LOG) {
                    LogDebug.d("loadClass", "加载 " + dc.plugin + " 失败");
                }
                Tasks.postDelayed2Thread(new Runnable() {
                    @Override
                    public void run() {
                        IPC.sendLocalBroadcast2All(context, new Intent("com.qihoo360.replugin.load_large_plugin.dismiss_dlg"));
                    }
                }, 300);
            }
            if (LOGR) {
                LogRelease.w(PLUGIN_TAG, "p m hlc dc failed: " + className + " t=" + dc.className + " tp=" + dc.classType + " df=" + dc.defClass);
            }
            // return dummy class
            if ("activity".equals(dc.classType)) {
                return DummyActivity.class;
            } else if ("service".equals(dc.classType)) {
                return DummyService.class;
            } else if ("provider".equals(dc.classType)) {
                return DummyProvider.class;
            }
            return dc.defClass;
        }

        //
        return loadDefaultClass(className);
    }

    /**
     * @param className
     * @return
     */
    private final Class<?> loadServiceClass(String className) {
        //
        Plugin p = mDefaultPlugin;
        if (p == null) {
            if (LOG) {
                LogDebug.d(PLUGIN_TAG, "plugin service loader: not found default plugin,  in=" + className);
            }
            return null;
        }

        ServiceInfo services[] = p.mLoader.mPackageInfo.services;
        if (services == null || services.length <= 0) {
            if (LOG) {
                LogDebug.d(PLUGIN_TAG, "plugin service loader: manifest not item found");
            }
            return null;
        }

        String service = services[0].name;

        ClassLoader cl = p.getClassLoader();
        if (LOG) {
            LogDebug.d(PLUGIN_TAG, "plugin service loader: in=" + className + " target=" + service);
        }
        Class<?> c = null;
        try {
            c = cl.loadClass(service);
        } catch (Throwable e) {
            if (LOG) {
                LogDebug.d(PLUGIN_TAG, e.getMessage(), e);
            }
        }
        if (LOG) {
            LogDebug.d(PLUGIN_TAG, "plugin service loader: c=" + c + ", loader=" + cl);
        }
        return c;
    }

    /**
     * @param className
     * @return
     */
    private final Class<?> loadProviderClass(String className) {
        //
        Plugin p = mDefaultPlugin;
        if (p == null) {
            if (LOG) {
                LogDebug.d(PLUGIN_TAG, "plugin provider loader: not found default plugin,  in=" + className);
            }
            return null;
        }

        ProviderInfo[] providers = p.mLoader.mPackageInfo.providers;
        if (providers == null || providers.length <= 0) {
            if (LOG) {
                LogDebug.d(PLUGIN_TAG, "plugin provider loader: manifest not item found");
            }
            return null;
        }

        String provider = providers[0].name;

        ClassLoader cl = p.getClassLoader();
        if (LOG) {
            LogDebug.d(PLUGIN_TAG, "plugin provider loader: in=" + className + " target=" + provider);
        }
        Class<?> c = null;
        try {
            c = cl.loadClass(provider);
        } catch (Throwable e) {
            if (LOG) {
                LogDebug.d(PLUGIN_TAG, e.getMessage(), e);
            }
        }
        if (LOG) {
            LogDebug.d(PLUGIN_TAG, "plugin provider loader: c=" + c + ", loader=" + cl);
        }
        return c;
    }

    /**
     * @param className
     * @return
     */
    private final Class<?> loadDefaultClass(String className) {
        //
        Plugin p = mDefaultPlugin;
        if (p == null) {
            if (PluginManager.isPluginProcess()) {
                if (LOG) {
                    LogDebug.d(PLUGIN_TAG, "plugin class loader: not found default plugin,  in=" + className);
                }
            }
            return null;
        }

        ClassLoader cl = p.getClassLoader();
        if (LOG) {
            LogDebug.d(PLUGIN_TAG, "plugin class loader: in=" + className);
        }
        Class<?> c = null;
        try {
            c = cl.loadClass(className);
        } catch (Throwable e) {
            if (LOG) {
                if (e != null && e.getCause() instanceof ClassNotFoundException) {
                    if (LOG) {
                        LogDebug.d(PLUGIN_TAG, "plugin classloader not found className=" + className);
                    }
                } else {
                    if (LOG) {
                        LogDebug.d(PLUGIN_TAG, e.getMessage(), e);
                    }
                }
            }
        }
        if (LOG) {
            LogDebug.d(PLUGIN_TAG, "plugin class loader: c=" + c + ", loader=" + cl);
        }
        return c;
    }

    void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        if (RePluginInternal.FOR_DEV) {
            // 是否加载插件指令
            {
                boolean load = false;
                for (String a : args) {
                    if (load) {
                        Context c = Factory.queryPluginContext(a);
                        writer.println("plugin.c=" + c);
                        return;
                    }
                    if (a.equals("--load")) {
                        load = true;
                    }
                }
            }

            // 是否启动插件进程
            {
                boolean load = false;
                for (String a : args) {
                    if (load) {
                        try {
                            PluginBinderInfo info = new PluginBinderInfo(PluginBinderInfo.BINDER_REQUEST);
                            /*IPluginClient client = */MP.startPluginProcess(a, IPluginManager.PROCESS_AUTO, info);
                        } catch (Throwable e) {
                            e.printStackTrace();
                        }
                        return;
                    }
                    if (a.equals("--start-plugin-process")) {
                        load = true;
                    }
                }
            }

            // dump原因
            {
                for (String a : args) {
                    if (a.equals("--reason")) {
                        writer.println("--- Reason ---");
                        if (Plugin.sLoadedReasons != null) {
                            for (String reason : Plugin.sLoadedReasons) {
                                writer.println(reason);
                            }
                        }
                        return;
                    }
                }
            }

            // dump binder原因
            {
                for (String a : args) {
                    if (a.equals("--binder-reason")) {
                        writer.println("--- Binder Reason ---");
                        if (MP.sBinderReasons != null) {
                            for (String key : MP.sBinderReasons.keySet()) {
                                writer.println("binder: " + key);
                                writer.println(MP.sBinderReasons.get(key));
                            }
                        }
                        return;
                    }
                }
            }

            // 是否启动插件指令
            {
                boolean start = false;
                String plugin = "";
                String activity = "";
                for (String a : args) {
                    if (start) {
                        if (TextUtils.isEmpty(plugin)) {
                            plugin = a;
                            continue;
                        }
                        if (TextUtils.isEmpty(activity)) {
                            activity = a;
                            continue;
                        }
                    }
                    if (a.equals("--start")) {
                        start = true;
                    }
                }
                if (start) {
                    if (!TextUtils.isEmpty(plugin) && !TextUtils.isEmpty(activity)) {
                        Intent intent = new Intent();
                        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        Factory.startActivity(mContext, intent, plugin, activity, IPluginManager.PROCESS_AUTO);
                    } else {
                        if (LOG) {
                            LogDebug.d(PLUGIN_TAG, "need {plugin} and {activity}");
                        }
                    }
                    return;
                }
            }

            ReflectUtils.dumpObject(this, fd, writer, args);
            writer.println();

            writer.println("--- plugins V2 ---");
            writer.println("--- plugins.size = " + mPlugins.size() + " ---");
            for (Plugin p : mPlugins.values()) {
                writer.println(p.mInfo);
            }
            writer.println();

            PluginProcessMain.dump(fd, writer, args);

            writer.println("--- plugins.cached objects ---");
            Plugin.dump(fd, writer, args);
            writer.println();
        }
    }

    final IBinder getHostBinder() {
        return mHostSvc;
    }

    final boolean isActivity(String name) {
        return mContainerActivities.contains(name);
    }

    final Plugin getPlugin(String plugin) {
        return mPlugins.get(plugin);
    }

    final Plugin loadPackageInfoPlugin(String plugin, PluginCommImpl pm) {
        Plugin p = Plugin.cloneAndReattach(mContext, mPlugins.get(plugin), mClassLoader, pm);
        return loadPlugin(p, Plugin.LOAD_INFO, true);
    }

    final Plugin loadResourcePlugin(String plugin, PluginCommImpl pm) {
        Plugin p = Plugin.cloneAndReattach(mContext, mPlugins.get(plugin), mClassLoader, pm);
        return loadPlugin(p, Plugin.LOAD_RESOURCES, true);
    }

    final Plugin loadDexPlugin(String plugin, PluginCommImpl pm) {
        Plugin p = Plugin.cloneAndReattach(mContext, mPlugins.get(plugin), mClassLoader, pm);
        return loadPlugin(p, Plugin.LOAD_DEX, true);
    }

    final Plugin loadAppPlugin(String plugin) {
        return loadPlugin(mPlugins.get(plugin), Plugin.LOAD_APP, true);
    }

    // 底层接口
    final Plugin loadPlugin(PluginInfo pi, PluginCommImpl pm, int loadType, boolean useCache) {
        Plugin p = Plugin.build(pi);
        p.attach(mContext, mClassLoader, pm);
        return loadPlugin(p, loadType, useCache);
    }

    // 底层接口
    final Plugin loadPlugin(Plugin p, int loadType, boolean useCache) {
        if (p == null) {
            return null;
        }
        if (!p.load(loadType, useCache)) {
            if (LOGR) {
                LogRelease.e(PLUGIN_TAG, "pmb.lp: f to l. lt=" + loadType + "; i=" + p.mInfo);
            }
            return null;
        }
        return p;
    }

    final Plugin lookupPlugin(ClassLoader loader) {
        for (Plugin p : mPlugins.values()) {
            if (p != null && p.getClassLoader() == loader) {
                return p;
            }
        }
        return null;
    }

    final void insertNewPlugin(PluginInfo info) {
        if (LOG) {
            LogDebug.d(PLUGIN_TAG, "insert new plugin: info=" + info);
        }
        synchronized (LOCKER) {

            // 检查插件是否已经被禁用
            if (RePlugin.getConfig().getCallbacks().isPluginBlocked(info)) {
                if (LOG) {
                    LogDebug.d(PLUGIN_TAG, "insert new plugin: plugin is blocked, in=" + info);
                }
                return;
            }

            Plugin p = mPlugins.get(info.getName());

            // 如果是内置插件，新插件extract成功，则直接替换
            // TODO 考虑加锁？
            if (p != null && p.mInfo.getType() == PluginInfo.TYPE_BUILTIN && info.getType() == PluginInfo.TYPE_PN_INSTALLED) {
                // next

            } else if (p != null && p.isInitialized()) {
                // 检查该插件是否已加载
                if (LOG) {
                    LogDebug.d(PLUGIN_TAG, "insert new plugin: failed cause plugin has loaded, plugin=" + info);
                }
                // 设置是否需要重启标志
                mNeedRestart = true;
                return;
            }

            // 此处直接使用该插件，没有考虑是否只采用最新版
            if (LOG) {
                LogDebug.d(PLUGIN_TAG, "insert new plugin: ok: plugin=" + info);
            }
            Plugin plugin = Plugin.build(info);
            plugin.attach(mContext, mClassLoader, mLocal);

            // 同时加入PackageName和Alias（如有）
            putPluginObject(info, plugin);
        }
    }

    final void newPluginFound(PluginInfo info, boolean persistNeedRestart) {
        // 更新最新插件表
        PluginTable.updatePlugin(info);

        // 更新可加载插件表
        insertNewPlugin(info);

        // 清空插件的状态（解禁）
        PluginStatusController.setStatus(info.getName(), info.getVersion(), PluginStatusController.STATUS_OK);

        if (IPC.isPersistentProcess()) {
            persistNeedRestart = mNeedRestart;
        }

        // 输出一个日志
        if (LOGR) {
            LogRelease.i(PLUGIN_TAG, "p.m. n p f n=" + info.getName() + " b1=" + persistNeedRestart + " b2=" + mNeedRestart);
        }

        // 通知本进程：通知给外部使用者
        Intent intent = new Intent(RePluginConstants.ACTION_NEW_PLUGIN);
        intent.putExtra(RePluginConstants.KEY_PLUGIN_INFO, info);
        intent.putExtra(RePluginConstants.KEY_PERSIST_NEED_RESTART, persistNeedRestart);
        intent.putExtra(RePluginConstants.KEY_SELF_NEED_RESTART, mNeedRestart);
        LocalBroadcastManager.getInstance(mContext).sendBroadcast(intent);
    }

    final void pluginUninstalled(PluginInfo info) {
        if (LOG) {
            LogDebug.d(PLUGIN_TAG, "Clear plugin cache. pn=" + info.getName());
        }

        // 移除卸载插件的HashMap缓存
        if (mPlugins.containsKey(info.getName())) {
            mPlugins.remove(info.getName());
        }

        // 移除卸载插件表快照
        PluginTable.removeInfo(info);

        // 移除内存中插件的PackageInfo、Resources、ComponentList和DexClassLoader缓存对象
        Plugin.clearCachedPlugin(Plugin.queryCachedFilename(info.getName()));
    }

    final IPluginClient startPluginProcessLocked(String plugin, int process, PluginBinderInfo info) {
        if (LOG) {
            LogDebug.d(PLUGIN_TAG, "start plugin process: plugin=" + plugin + " info=" + info);
        }

        // 强制使用UI进程
        if (Constant.ENABLE_PLUGIN_ACTIVITY_AND_BINDER_RUN_IN_MAIN_UI_PROCESS) {
            if (info.request == PluginBinderInfo.ACTIVITY_REQUEST) {
                if (process == IPluginManager.PROCESS_AUTO) {
                    process = IPluginManager.PROCESS_UI;
                }
            }
            if (info.request == PluginBinderInfo.BINDER_REQUEST) {
                if (process == IPluginManager.PROCESS_AUTO) {
                    process = IPluginManager.PROCESS_UI;
                }
            }
        }

        //
        PluginProcessMain.schedulePluginProcessLoop(PluginProcessMain.CHECK_STAGE1_DELAY);

        //自定义进程启动流程
        // 第一步: 先尝试从缓存中查找
        IPluginClient client = PluginProcessMain.probePluginClient(plugin, process, info);
        if (client != null) {
            if (LOG) {
                LogDebug.d(PLUGIN_TAG, "start plugin process: probe client ok, already running, plugin=" + plugin + " client=" + client);
            }
            return client;
        }

        // 第二步: 缓存中未找到，先分配一个进程坑位（这个分支需要优化）
        int index = IPluginManager.PROCESS_AUTO;
        try {
            //分配进程坑位，这里的代码在2.1.7以后的版本会优化，大部分情况下 process == index
            index = PluginProcessMain.allocProcess(plugin, process);
            if (LOG) {
                LogDebug.d(PLUGIN_TAG, "start plugin process: alloc process ok, plugin=" + plugin + " index=" + index);
            }
        } catch (Throwable e) {
            if (LOGR) {
                LogRelease.e(PLUGIN_TAG, "a.p.p: " + e.getMessage(), e);
            }
        }
        // 分配的坑位不属于UI、和自定义进程，就返回。
        if (!(index == IPluginManager.PROCESS_UI
                || PluginProcessHost.isCustomPluginProcess(index)
                || PluginManager.isPluginProcess(index))) {
            return null;
        }

        // 第三步: 启动进程
        boolean rc = PluginProviderStub.proxyStartPluginProcess(mContext, index);
        if (LOG) {
            LogDebug.d(PLUGIN_TAG, "start plugin process: start process ok, plugin=" + plugin + " index=" + index);
        }
        if (!rc) {
            return null;
        }

        // 再次获取
        client = PluginProcessMain.probePluginClient(plugin, process, info);
        if (client == null) {
            if (LOGR) {
                LogRelease.e(PLUGIN_TAG, "spp pc n");
            }
            return null;
        }

        if (LOG) {
            LogDebug.d(PLUGIN_TAG, "start plugin process: probe client ok, plugin=" + plugin + " index=" + info.index);
        }

        return client;
    }

    final IHostBinderFetcher getBuiltinPlugin(String plugin) {
        synchronized (mBuiltinPlugins) {
            return mBuiltinPlugins.get(plugin);
        }
    }

    final HashMap<String, IModule> getBuiltinModules(String plugin) {
        return mBuiltinModules.get(plugin);
    }

    final void handleServiceCreated(Service service) {
//      int pid = Process.myPid();
        try {
            PluginProcessMain.getPluginHost().regService(PluginManager.sPluginProcessIndex, mDefaultPlugin.mInfo.getName(), service.getClass().getName());
        } catch (Throwable e) {
            if (LOGR) {
                LogRelease.e(PLUGIN_TAG, "r.s: " + e.getMessage(), e);
            }
        }

//      // TODO 设置插件服务类的类加载器吗？
//      Intent intent = service.getIntent();
//      if (intent != null) {
//          if (LOG) {
//              LogDebug.d(PLUGIN_TAG, "set service intent cl=" + service.getClassLoader());
//          }
//          intent.setExtrasClassLoader(service.getClassLoader());
//      }
    }

    final void handleServiceDestroyed(Service service) {
//      int pid = Process.myPid();
        try {
            PluginProcessMain.getPluginHost().unregService(PluginManager.sPluginProcessIndex, mDefaultPlugin.mInfo.getName(), service.getClass().getName());
        } catch (Throwable e) {
            if (LOGR) {
                LogRelease.e(PLUGIN_TAG, "ur.s: " + e.getMessage(), e);
            }
        }
    }
}
