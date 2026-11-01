package app.hushfeed.verification;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Passive 47.6.3 feed observation. It never creates a native player, seeks or dispatches input. */
final class AutoAdvanceWatch implements Application.ActivityLifecycleCallbacks {
    private static final String PACKAGE="com.zhiliaoapp.musically";
    private static final String MAIN="com.ss.android.ugc.aweme.main.MainActivity";
    private static final String PAGER="com.ss.android.ugc.aweme.common.widget.VerticalViewPager";
    private static final int HOLDER_TAG=0x7f0a2d04,MAX_NODES=6000;
    private static final Handler MAIN_THREAD=new Handler(Looper.getMainLooper());
    private static AutoAdvanceWatch instance;
    private final ClassLoader loader;
    private final boolean bound;
    private WeakReference<Activity> resumed=new WeakReference<>(null);
    private Bindings bindings;
    private boolean running;
    private AutoAdvanceWatch(ClassLoader loader,boolean bound){this.loader=loader;this.bound=bound;}
    static void bootstrap(Application app,ClassLoader loader){
        if(instance!=null)return;
        boolean supported=false;
        try{PackageInfo info=app.getPackageManager().getPackageInfo(PACKAGE,0);supported=PACKAGE.equals(app.getPackageName()) && "47.6.3".equals(info.versionName)
                && (info.versionCode==2024706030 || info.versionCode==Integer.MAX_VALUE);}catch(Exception ignored){}
        instance=new AutoAdvanceWatch(loader,supported);
        // Registered before TikTok replaces the Instrumentation. No extension Activity helper is needed.
        app.registerActivityLifecycleCallbacks(instance);
    }
    static void start(ClassLoader settingsLoader,Intent intent){
        String text=intent.getStringExtra("seconds");int seconds=text==null?25:Integer.parseInt(text);
        if(seconds<1 || seconds>AutoAdvanceWatchState.MAX_SECONDS)throw new IllegalArgumentException("startwatch seconds must be 1..60");
        MAIN_THREAD.post(()->{
            if(instance==null || !instance.bound || instance.running){unavailable();return;}
            instance.begin(settingsLoader,seconds);
        });
    }
    private static boolean mode(ClassLoader loader)throws Exception{
        Object setting=loader.loadClass("app.morphe.extension.tiktok.settings.Settings").getField("AUTO_ADVANCE").get(null);
        Object value=setting.getClass().getMethod("get").invoke(setting);
        if(!(value instanceof Boolean))throw new IllegalStateException();
        Object paused=loader.loadClass("app.morphe.extension.shared.settings.Setting").getMethod("isPaused").invoke(null);
        if(!Boolean.FALSE.equals(paused))throw new IllegalStateException("paused host");
        return (Boolean)value;
    }
    private static void unavailable(){
        AutoAdvanceWatchState state=new AutoAdvanceWatchState();state.observe(null);
        Log.i(Probe.TAG,state.report(false,SystemClock.elapsedRealtime()));
    }
    private void begin(ClassLoader settingsLoader,int seconds){
        final boolean initialMode;
        try{initialMode=mode(settingsLoader);}catch(Exception failure){unavailable();return;}
        running=true;final long until=SystemClock.elapsedRealtime()+seconds*1000L;
        AutoAdvanceWatchState state=new AutoAdvanceWatchState();
        Runnable sample=new Runnable(){public void run(){
            long now=SystemClock.elapsedRealtime();
            try{state.observe(capture());}
            catch(Missing unavailable){state.unavailable(unavailable.reason);}
            catch(Exception|LinkageError unavailable){state.unavailable("native_reader_unavailable");}
            if(now<until && !state.advanceObserved()){MAIN_THREAD.postDelayed(this,50);return;}
            boolean stable=false;try{stable=mode(settingsLoader)==initialMode;}catch(Exception ignored){}
            running=false;Log.i(Probe.TAG,state.report(stable,SystemClock.elapsedRealtime()));
        }};
        MAIN_THREAD.post(sample);Log.i(Probe.TAG,"ok startwatch pending");
    }
    public void onActivityResumed(Activity activity){resumed=new WeakReference<>(activity);}
    public void onActivityPaused(Activity activity){if(resumed.get()==activity)resumed.clear();}
    public void onActivityDestroyed(Activity activity){if(resumed.get()==activity)resumed.clear();}
    public void onActivityCreated(Activity a,Bundle b){}
    public void onActivityStarted(Activity a){}
    public void onActivityStopped(Activity a){}
    public void onActivitySaveInstanceState(Activity a,Bundle b){}
    private AutoAdvanceWatchState.Frame capture()throws Exception{
        if(!bound)throw new Missing("identity_unavailable");
        Activity activity=resumed.get();
        if(activity==null || activity.isFinishing() || activity.isDestroyed() || !MAIN.equals(activity.getClass().getName()))throw new Missing("resumed_native_main_unavailable");
        if(!activity.hasWindowFocus())throw new Missing("window_not_focused");
        View decor=activity.getWindow().getDecorView();
        if(!visible(decor) || decor.getWindowToken()==null)throw new Missing("native_window_unavailable");
        requireSingleWindow(decor);
        if(bindings==null)bindings=new Bindings(loader);
        int homeId=activity.getResources().getIdentifier("onv","id",PACKAGE);
        View home=homeId==0?null:decor.findViewById(homeId);
        if(!visible(home) || !home.isSelected() || home.getRootView()!=decor)throw new Missing("native_home_not_selected");
        ViewGroup pager=null;ArrayDeque<View> queue=new ArrayDeque<>();queue.add(decor);int nodes=0;
        int sheet=activity.getResources().getIdentifier("pwx","id",PACKAGE);
        int title=activity.getResources().getIdentifier("wl7","id",PACKAGE);
        if(sheet==0 || title==0)throw new Missing("native_sheet_anchors_unavailable");
        while(!queue.isEmpty()){
            if(++nodes>MAX_NODES)throw new Missing("view_bound");
            View view=queue.removeFirst();
            if(visible(view)){
                if((sheet!=0 && view.getId()==sheet) || (title!=0 && view.getId()==title))throw new Missing("comment_sheet_present");
                if(PAGER.equals(view.getClass().getName())){
                    if(pager!=null)throw new Missing("ambiguous_native_pager");
                    pager=(ViewGroup)view;
                }
            }
            if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int n=0;n<group.getChildCount();n++)queue.add(group.getChildAt(n));}
        }
        if(pager==null || number(call(pager,"getScrollState"))!=0)throw new Missing("settled_native_pager_unavailable");
        Object adapter=call(pager,"getAdapter");int index=(int)number(call(pager,"getCurrentItem"));
        if(index<0 || !bindings.adapter.isInstance(adapter))throw new Missing("native_adapter_unavailable");
        Rect pagerBounds=bounds(pager);if(pagerBounds==null)throw new Missing("pager_became_not_visible");View cell=null;Object holder=null;
        for(int n=0;n<pager.getChildCount();n++){
            View child=pager.getChildAt(n);Rect area=bounds(child);
            if(child.getTag(HOLDER_TAG)==null || area==null || size(area)*100<size(pagerBounds)*85)continue;
            if(cell!=null)throw new Missing("ambiguous_native_cell");cell=child;holder=child.getTag(HOLDER_TAG);
        }
        if(cell==null || !bindings.cell.isInstance(holder))throw new Missing("native_cell_unavailable");
        Object model=call(adapter,"getItem",int.class,index);
        if(model==null || read(holder,"LLJLLIL")!=model)throw new Missing("holder_model_mismatch");
        Object ability=read(holder,"T"),panel=read(holder,"S");
        if(panel==null){Object fragment=read(holder,"LLJZ");if(!bindings.fragment.isInstance(fragment))throw new Missing("existing_panel_unavailable");panel=read(fragment,"LLJJJJ");}
        if(!bindings.panel.isInstance(panel) || read(panel,"LLJZ")!=pager || read(panel,"LLLIIIIL")!=adapter)throw new Missing("panel_owner_mismatch");
        if(ability==null)ability=read(panel,"LLLLLLLZIL");
        if(!bindings.ability.isInstance(ability) || read(ability,"LLJJJJ")!=panel)throw new Missing("component_owner_mismatch");
        Object controller=read(ability,"LLJLILLLLZIIL");
        if(!bindings.controller.isInstance(controller) || read(panel,"LLLLLLLLLL")!=controller)throw new Missing("controller_owner_mismatch");
        Object manager=read(controller,"mPlayerManager");if(manager==null)throw new Missing("existing_manager_unavailable");
        Object key=call(model,"getAid"),controllerModel=call(controller,"LLJJI");
        if(!(key instanceof String) || ((String)key).isEmpty() || controllerModel==null || !key.equals(call(controllerModel,"getAid")))throw new Missing("controller_model_mismatch");
        Object images=call(model,"getImageInfos"),status=call(model,"getStatus"),video=call(model,"getVideo");
        boolean photo=images instanceof Collection && !((Collection<?>)images).isEmpty()
                || call(model,"getPhotoModeImageInfo")!=null || call(model,"getPhotoModeTextInfo")!=null;
        boolean eligible=!photo && !bool(call(model,"isAd")) && number(call(model,"getLiveId"))==0 && status!=null
                && !bool(call(status,"isPrivate")) && number(call(status,"getPrivateStatus"))==0
                && video!=null && number(call(video,"getDuration"))>0;
        if(!eligible)throw new Missing("unsupported_or_private_cell");
        AutoAdvanceWatchState.SourceEvidence source=AutoAdvanceWatchState.readSource(new AutoAdvanceWatchState.SourceRead(){
            public Object source()throws Exception{return read(controller,"mCurrentSourceId");}
            public Object model()throws Exception{return call(adapter,"getItem",int.class,index);}
            public String key(Object value)throws Exception{Object key=value==null?null:call(value,"getAid");return key instanceof String?(String)key:null;}
            public long position()throws Exception{Object value=call(manager,"getCurrentPosition");if(!(value instanceof Number))throw new Missing("native_position_unavailable");return number(value);}
            public boolean playing()throws Exception{Object value=call(manager,"isPlaying");if(!(value instanceof Boolean))throw new Missing("native_play_state_unavailable");return (Boolean)value;}
            public boolean paused()throws Exception{Object value=call(manager,"isPaused");if(!(value instanceof Boolean))throw new Missing("native_pause_state_unavailable");return (Boolean)value;}
        });
        if(!source.valid() || source.model!=model || !key.equals(source.key))throw new Missing("native_source_changed_or_mismatched");
        // Re-read the native bindings after the player queries. No lazy getter creates a manager or panel.
        if(resumed.get()!=activity || !activity.hasWindowFocus() || cell.getParent()!=pager || !visible(cell)
                || cell.getTag(HOLDER_TAG)!=holder || read(holder,"LLJLLIL")!=model || call(adapter,"getItem",int.class,index)!=model
                || call(pager,"getAdapter")!=adapter || number(call(pager,"getCurrentItem"))!=index || number(call(pager,"getScrollState"))!=0
                || read(controller,"mPlayerManager")!=manager || read(ability,"LLJLILLLLZIIL")!=controller
                || !key.equals(read(controller,"mCurrentSourceId")) || !key.equals(call(model,"getAid"))
                || !key.equals(call(call(controller,"LLJJI"),"getAid")))throw new Missing("native_owner_changed_during_read");
        return new AutoAdvanceWatchState.Frame(new Object[]{activity,decor.getWindowToken(),pager,adapter,cell,holder,model,ability,controller,manager},
                (String)key,index,source.position,number(call(video,"getDuration")),SystemClock.elapsedRealtime(),source.playing,source.paused);
    }
    private static void requireSingleWindow(View decor)throws Exception{
        Class<?> type=Class.forName("android.view.WindowManagerGlobal");Object global=method(type,"getInstance").invoke(null),values;
        try{values=method(type,"getWindowViews").invoke(global);}catch(NoSuchMethodException absent){values=field(type,"mViews").get(global);}
        if(!(values instanceof List))throw new Missing("window_roots_unavailable");
        boolean found=false;int count=0;
        for(Object value:new ArrayList<>((List<?>)values)){
            if(++count>32)throw new Missing("window_bound");
            if(!(value instanceof View))throw new Missing("window_roots_unavailable");
            View root=(View)value;if(!visible(root))continue;
            if(root==decor)found=true;else throw new Missing("additional_visible_window");
        }
        if(!found)throw new Missing("native_window_unavailable");
    }
    private static final class Bindings{
        final Class<?> cell,adapter,ability,panel,controller,fragment;
        Bindings(ClassLoader loader)throws Exception{cell=loader.loadClass("com.ss.android.ugc.aweme.feed.adapter.VideoViewCell");adapter=loader.loadClass("X.07H8");ability=loader.loadClass("com.ss.android.ugc.feed.platform.panel.player.PlayerComponentTemp");panel=loader.loadClass("com.ss.android.ugc.aweme.feed.panel.BaseListFragmentPanel");controller=loader.loadClass("com.ss.android.ugc.aweme.feed.controller.PlayerController");fragment=loader.loadClass("com.ss.android.ugc.aweme.feed.ui.FeedRecommendFragment");}
    }
    private static final class Missing extends Exception{final String reason;Missing(String reason){this.reason=reason;}}
    private static boolean bool(Object value){return Boolean.TRUE.equals(value);}
    private static long number(Object value){return value instanceof Number?((Number)value).longValue():-1;}
    private static Rect bounds(View view){if(view==null || !view.isShown() || !view.isAttachedToWindow() || view.getWindowToken()==null)return null;Rect value=new Rect();return view.getGlobalVisibleRect(value) && value.width()>1 && value.height()>1?value:null;}
    private static boolean visible(View view){return bounds(view)!=null;}
    private static long size(Rect value){return value==null?0:(long)value.width()*value.height();}
    private static Object read(Object value,String name)throws Exception{return field(value.getClass(),name).get(value);}
    private static Field field(Class<?> type,String name)throws Exception{for(Class<?> c=type;c!=null;c=c.getSuperclass())try{Field f=c.getDeclaredField(name);f.setAccessible(true);return f;}catch(NoSuchFieldException next){}throw new NoSuchFieldException();}
    private static Object call(Object value,String name)throws Exception{return method(value.getClass(),name).invoke(value);}
    private static Object call(Object value,String name,Class<?> type,Object argument)throws Exception{return method(value.getClass(),name,type).invoke(value,argument);}
    private static Method method(Class<?> type,String name,Class<?>...args)throws Exception{for(Class<?> c=type;c!=null;c=c.getSuperclass())try{Method m=c.getDeclaredMethod(name,args);m.setAccessible(true);return m;}catch(NoSuchMethodException next){}return type.getMethod(name,args);}
}
