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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.IBinder;
import android.os.RemoteException;
import android.support.v4.content.LocalBroadcastManager;
import android.text.TextUtils;

import com.qihoo360.i.IPluginManager;
import com.qihoo360.loader2.alc.ActivityController;
import com.qihoo360.replugin.RePlugin;
import com.qihoo360.replugin.base.IPC;
import com.qihoo360.replugin.base.LocalBroadcastHelper;
import com.qihoo360.replugin.component.dummy.ForwardActivity;
import com.qihoo360.replugin.component.process.PluginProcessHost;
import com.qihoo360.replugin.component.receiver.PluginReceiverHelper;
import com.qihoo360.replugin.component.service.server.IPluginServiceServer;
import com.qihoo360.replugin.component.service.server.PluginServiceServer;
import com.qihoo360.replugin.helper.LogDebug;
import com.qihoo360.replugin.helper.LogRelease;

import java.util.HashMap;
import java.util.HashSet;

import static com.qihoo360.replugin.helper.LogDebug.LOG;
import static com.qihoo360.replugin.helper.LogDebug.PLUGIN_TAG;
import static com.qihoo360.replugin.helper.LogRelease.LOGR;

/**
 * @author RePlugin Team
 */
class PluginProcessPer extends IPluginClient.Stub {

    private final Context mContext;

    private final PmBase mPluginMgr;

    final PluginServiceServer mServiceMgr;

    final PluginContainers mACM; // TODO 考虑去掉 {package}权限

    private Plugin mDefaultPlugin;

    /**
     * 保存 plugin-receiver -> Receiver 的关系
     */
    private HashMap<String, BroadcastReceiver> mReceivers = new HashMap<>();

    PluginProcessPer(Context context, PmBase pm, int process, HashSet<String> containers) {
        mContext = context;
        mPluginMgr = pm;

        //luginServiceServer类，这个类是Replugin中的一个核心类，主要负责了对Service的提供和调度工作，
        // 例如startService、stopService、bindService、unbindService全部都由这个类管理
        mServiceMgr = new PluginServiceServer(context);

        //
        mACM = new PluginContainers();
        mACM.init(process, containers);
    }

    final void init(Plugin p) {
        mDefaultPlugin = p;
    }

    /**
     * 类加载器根据容器解析到目标的activity
     *
     * 这里先从PluginContainers的实例对象mACM中去查找ActivityState，对这个类还有印象吗？它就是在分配坑位的时候，
     * 我们用来保存坑位组件与真实组件对应关系的类。然后在缓存中找到插件名对应的插件对象，因为在分配坑位的时候插件信息已经加载过了，
     * 不需要重新加载。接着取出插件的ClassLoader对象，这个对象正是加载插件时创建的PuginDexClassLoader的实例了。
     * 然后利用插件的PuginDexClassLoader对象来加载真实Activity的class对象
     */
    final Class<?> resolveActivityClass(String container) {
        String plugin = null;
        String activity = null;

        // 先找登记的，如果找不到，则用forward activity
        PluginContainers.ActivityState state = mACM.lookupByContainer(container); //找到坑位Activity与真实Activity的对应关系对象
        if (state == null) {
            // PACM: loadActivityClass, not register, use forward activity, container=
            if (LOGR) {
                LogRelease.w(PLUGIN_TAG, "use f.a, c=" + container);
            }
            return ForwardActivity.class;
        }
        plugin = state.plugin;
        activity = state.activity;

        if (LOG) {
            // 启动 Start plugin demo1 打印日志
            // PACM: loadActivityClass in=com.qihoo360.replugin.sample.host.loader.a.ActivityN1NRNTS2 target=com.qihoo360.replugin.sample.demo1.MainActivity plugin=com.qihoo360.replugin.sample.demo1
            LogDebug.d(PLUGIN_TAG, "PACM: loadActivityClass in=" + container + " target=" + activity + " plugin=" + plugin);
        }

        //通过插件名从缓存中加载Plugin对象
        Plugin p = mPluginMgr.loadAppPlugin(plugin);
        if (p == null) {
            // PACM: loadActivityClass, not found plugin
            if (LOGR) {
                LogRelease.e(PLUGIN_TAG, "load fail: c=" + container + " p=" + plugin + " t=" + activity);
            }
            return null;
        }

        ClassLoader cl = p.getClassLoader();
        if (LOG) {
            LogDebug.d(PLUGIN_TAG, "PACM: loadActivityClass, plugin activity loader: in=" + container + " activity=" + activity);
        }
        Class<?> c = null;
        try {
            c = cl.loadClass(activity);
        } catch (Throwable e) {
            if (LOGR) {
                LogRelease.e(PLUGIN_TAG, e.getMessage(), e);
            }
        }
        if (LOG) {
            LogDebug.d(PLUGIN_TAG, "PACM: loadActivityClass, plugin activity loader: c=" + c + ", loader=" + cl);
        }

        return c;
    }

    //加载插件; 找到目标Activity；搜索匹配容器；加载目标Activity类；建立临时映射；返回容器
    // target为启动的 Activity  例如:com.qihoo360.replugin.sample.demo1.MainActivity
    @Override
    public String allocActivityContainer(String plugin, int process, String target, Intent intent) throws RemoteException {
        // 一旦有分配，则进入监控状态（一是避免不退出的情况，二也是最重要的是避免现在就退出的情况）
        RePlugin.getConfig().getEventCallbacks().onPrepareAllocPitActivity(intent);

        String loadPlugin = null;
        // 如果UI进程启用，尝试使用传过来的插件，强制用UI进程
        if (Constant.ENABLE_PLUGIN_ACTIVITY_AND_BINDER_RUN_IN_MAIN_UI_PROCESS) {
            if (IPC.isUIProcess()) {
                loadPlugin = plugin;
                process = IPluginManager.PROCESS_UI;
            } else {
                loadPlugin = plugin;
            }
        }
        // 如果不成，则再次尝试使用默认插件
        if (TextUtils.isEmpty(loadPlugin)) {
            if (mDefaultPlugin == null) {
                if (LOGR) {
                    LogRelease.e(PLUGIN_TAG, "a.a.c p i n");
                }
                return null;
            }
            loadPlugin = mDefaultPlugin.mInfo.getName();
        }
        //获取坑位重点函数bindActivity
        String container = bindActivity(loadPlugin, process, target, intent);
        if (LOG) {
            // PACM: eval plugin com.qihoo360.replugin.sample.demo1, target=com.qihoo360.replugin.sample.demo1.MainActivity, container=com.qihoo360.replugin.sample.host.loader.a.ActivityN1NRNTS2
            // PACM: eval plugin com.qihoo360.replugin.sample.demo1, target=com.qihoo360.replugin.sample.demo1.activity.standard.StandardActivity, container=com.qihoo360.replugin.sample.host.loader.a.ActivityN1NRNTS4
            // PACM: eval plugin com.qihoo360.replugin.sample.demo1, target=com.qihoo360.replugin.sample.demo1.activity.single_top.SingleTopActivity1, container=com.qihoo360.replugin.sample.host.loader.a.ActivityN1STPNTS1
            LogDebug.d(PLUGIN_TAG, "PACM: eval plugin " + loadPlugin + ", target=" + target + ", container=" + container);
        }
        return container;
    }

    @Override
    public IBinder queryBinder(String plugin, String binder) throws RemoteException {
        Plugin p = null;
        if (TextUtils.isEmpty(plugin)) {
            p = mDefaultPlugin;
        } else {
            p = mPluginMgr.loadAppPlugin(plugin);
        }

        if (p == null) {
            if (LOGR) {
                LogRelease.e(PLUGIN_TAG, "q.b p i n");
            }
            return null;
        }
        if (p.mLoader == null) {
            if (LOGR) {
                LogRelease.e(PLUGIN_TAG, "q.b p l i n");
            }
            return null;
        }
        if (p.mLoader.mBinderPlugin == null) {
            if (LOGR) {
                LogRelease.e(PLUGIN_TAG, "q.b p l b i n");
            }
            return null;
        }
        if (p.mLoader.mBinderPlugin.mPlugin == null) {
            if (LOGR) {
                LogRelease.e(PLUGIN_TAG, "q.b p l b p i n");
            }
            return null;
        }
        IBinder b = p.mLoader.mBinderPlugin.mPlugin.query(binder);
        if (LOG) {
            LogDebug.d(PLUGIN_TAG, "PluginImpl.query: call plugin aidl: plugin=" + p.mInfo.getName() + " binder.name=" + binder + " binder.object=" + b);
        }
        if (b != null) {
            // TODO 增加计数器
        }
        return b;
    }

    @Override
    public void releaseBinder() throws RemoteException {
        if (LOG) {
            LogDebug.d(PLUGIN_TAG, "PluginImpl.releaseBinder");
        }
        // 告诉外界Binder已经被释放
        RePlugin.getConfig().getEventCallbacks().onBinderReleased();
    }

    @Override
    public void sendIntent(Intent intent) throws RemoteException {
        sendIntent(intent, false);
    }

    @Override
    public void sendIntentSync(Intent intent) throws RemoteException {
        sendIntent(intent, true);
    }

    private void sendIntent(Intent intent, boolean sync) throws RemoteException {
        if (LOG) {
            LogDebug.d(PLUGIN_TAG, "sendIntent pr=" + IPC.getCurrentProcessName() + " intent=" + intent);
        }
        intent.setExtrasClassLoader(getClass().getClassLoader());
        if (sync) {
            LocalBroadcastHelper.sendBroadcastSyncUi(mContext, intent);
        } else {
            LocalBroadcastManager.getInstance(mContext).sendBroadcast(intent);
        }
    }

    @Override
    public int sumActivities() throws RemoteException {
        return ActivityController.sumActivities();
    }

    @Override
    public IPluginServiceServer fetchServiceServer() throws RemoteException {
        return mServiceMgr.getService();
    }

    /**
     * 加载插件；找到目标Activity；搜索匹配容器；加载目标Activity类；建立临时映射；返回容器
     *
     * @param plugin   插件名称
     * @param process  进程
     * @param activity Activity 名称
     * @param intent   调用者传入的 Intent
     * @return 坑位
     */
    final String bindActivity(String plugin, int process, String activity, Intent intent) {

        /* 获取插件对象,这时候一般是加载过了，从缓存获取 */
        Plugin p = mPluginMgr.loadAppPlugin(plugin);
        if (p == null) {
            if (LOG) {
                LogDebug.w(PLUGIN_TAG, "PACM: bindActivity: may be invalid plugin name or load plugin failed: plugin=" + plugin);
            }
            return null;
        }

        /* 获取 ActivityInfo */
        ActivityInfo ai = p.mLoader.mComponents.getActivity(activity);
        if (ai == null) {
            if (LOG) {
                LogDebug.d(PLUGIN_TAG, "PACM: bindActivity: activity not found: activity=" + activity);
            }
            return null;
        }

        if (ai.processName == null) {
            ai.processName = ai.applicationInfo.processName;
        }
        if (ai.processName == null) {
            ai.processName = ai.packageName;
        }

        /* 获取 Container */
        String container;

        // 自定义进程
        //container是ActivityState里面字段。返回一个AcitivtyState对象，这里面保存了坑位Activity和真实要启动的Activity之间的对应关系
        if (ai.processName.contains(PluginProcessHost.PROCESS_PLUGIN_SUFFIX2)) {
            String processTail = PluginProcessHost.processTail(ai.processName);
            container = mACM.alloc2(ai, plugin, activity, process, intent, processTail);
        } else {
            container = mACM.alloc(ai, plugin, activity, process, intent);
        }

        if (TextUtils.isEmpty(container)) {
            if (LOG) {
                LogDebug.w(PLUGIN_TAG, "PACM: bindActivity: activity container is empty");
            }
            return null;
        }

        if (LOG) {
            // PACM: bindActivity: lookup activity container: container=com.qihoo360.replugin.sample.host.loader.a.ActivityN1NRNTS2
            // PACM: bindActivity: lookup activity container: container=com.qihoo360.replugin.sample.host.loader.a.ActivityN1NRNTS4
            // PACM: bindActivity: lookup activity container: container=com.qihoo360.replugin.sample.host.loader.a.ActivityN1STPNTS1
            LogDebug.d(PLUGIN_TAG, "PACM: bindActivity: lookup activity container: container=" + container);
        }

        /* 检查 activity 是否存在 */
        Class<?> c = null;
        try {
            c = p.mLoader.mClassLoader.loadClass(activity);
        } catch (Throwable e) {
            if (LOGR) {
                LogRelease.e(PLUGIN_TAG, e.getMessage(), e);
            }
        }
        if (c == null) {
            if (LOG) {
                LogDebug.w(PLUGIN_TAG, "PACM: bindActivity: plugin activity class not found: c=" + activity);
            }
            return null;
        }

        return container;
    }

    @Override
    public void onReceive(String plugin, final String receiver, final Intent intent) {
        PluginReceiverHelper.onPluginReceiverReceived(plugin, receiver, mReceivers, intent);
    }
}
