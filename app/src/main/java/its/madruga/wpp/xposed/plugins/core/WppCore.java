package its.madruga.wpp.xposed.plugins.core;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.drawable.Drawable;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashSet;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import its.madruga.wpp.xposed.Unobfuscator;

public class WppCore {

    private static Object mainActivity;
    private static Field contactManagerField;
    private static Method getContactMethod;
    private static Class<?> mGenJidClass;
    private static Method mGenJidMethod;
    private static Class bottomDialog;
    private static Activity mConversation;
    private static Field convChatField;
    private static Field chatJidField;

    private static HashSet<ObjectOnChangeListener> listenerChat = new HashSet<>();

    public interface ObjectOnChangeListener {
        void onChange(Object object, String type);

    }


    public static void Initialize(ClassLoader loader) {
        try {

            // init Main activity
            XposedBridge.hookAllMethods(XposedHelpers.findClass("com.whatsapp.HomeActivity", loader), "onCreate", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    mainActivity = param.thisObject;
                }
            });

            XposedBridge.hookAllMethods(XposedHelpers.findClass("com.whatsapp.HomeActivity", loader), "onResume", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    mainActivity = param.thisObject;
                }
            });

            // init ContactManager
            contactManagerField = Unobfuscator.loadContactManagerField(loader);
            getContactMethod = Unobfuscator.loadGetContactInfoMethod(loader);

            // init UserJID
            var mSendReadClass = XposedHelpers.findClass("com.whatsapp.jobqueue.job.SendReadReceiptJob", loader);
            var subClass = Arrays.stream(mSendReadClass.getConstructors()).filter(c -> c.getParameterTypes().length == 8).findFirst().orElse(null).getParameterTypes()[0];
            mGenJidClass = Arrays.stream(subClass.getFields()).filter(field -> Modifier.isStatic(field.getModifiers())).findFirst().orElse(null).getType();
            mGenJidMethod = Arrays.stream(mGenJidClass.getMethods()).filter(m -> m.getParameterCount() == 1 && !Modifier.isStatic(m.getModifiers())).findFirst().orElse(null);
            // Bottom Dialog
            bottomDialog = Unobfuscator.loadDialogViewClass(loader);

            // Conversation
            var onStartMethod = Unobfuscator.loadAntiRevokeOnStartMethod(loader);
            var onResumeMethod = Unobfuscator.loadAntiRevokeOnResumeMethod(loader);
            convChatField = Unobfuscator.loadAntiRevokeConvChatField(loader);
            chatJidField = Unobfuscator.loadAntiRevokeChatJidField(loader);
            XposedBridge.hookMethod(onStartMethod, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    mConversation = (Activity) param.thisObject;
                    for (ObjectOnChangeListener listener : listenerChat) {
                        listener.onChange(mConversation, "onStartConversation");
                    }
                }
            });

            XposedBridge.hookMethod(onResumeMethod, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    mConversation = (Activity) param.thisObject;
                    for (ObjectOnChangeListener listener : listenerChat) {
                        listener.onChange(mConversation, "onResumeConversation");
                    }
                }
            });
        } catch (Exception e) {
            XposedBridge.log(e);
        }
    }

    public static Object getContactManager() {
        if (mainActivity == null) return null;
        try {
            contactManagerField.setAccessible(true);
            return contactManagerField.get(mainActivity);
        } catch (Exception e) {
            XposedBridge.log(e);
        }
        return null;
    }

    public static String getContactName(Object userJid) {
        try {
            var contact = getContactMethod.invoke(getContactManager(), userJid);
            var stringField = Arrays.stream(contact.getClass().getDeclaredFields()).filter(f -> f.getType().equals(String.class)).toArray(Field[]::new);
            return (String) stringField[3].get(contact);
        } catch (Exception e) {
            XposedBridge.log(e);
        }
        return null;
    }

    public static Object createUserJid(String rawjid) {
        var genInstance = XposedHelpers.newInstance(mGenJidClass);
        try {
            return mGenJidMethod.invoke(genInstance, rawjid);
        } catch (Exception e) {
            XposedBridge.log(e);
        }
        return null;
    }

    public static String getRawString(Object userjid) {
        if (userjid == null) return null;
        return (String) XposedHelpers.callMethod(userjid, "getRawString");
    }

    public static String getCurrentRawJID() {
        if (mConversation == null) return null;
        var chatField = XposedHelpers.getObjectField(mConversation, convChatField.getName());
        var chatJidObj = XposedHelpers.getObjectField(chatField, chatJidField.getName());
        return getRawString(chatJidObj);
    }

    public static String stripJID(String str) {
        try {
            return (str.contains("@g.us") || str.contains("@s.whatsapp.net") || str.contains("@broadcast")) ? str.substring(0, str.indexOf("@")) : str;
        } catch (Exception e) {
            XposedBridge.log(e.getMessage());
            return str;
        }
    }


    public static Drawable getContactPhoto(String jid) {
        String datafolder = Utils.getApplication().getCacheDir().getParent() + "/";
        File file = new File(datafolder + "files" + "/" + "Avatars" + "/" + jid + ".j");

        if (!file.exists())
            file = new File(datafolder + "/cache/" + "Profile Pictures" + "/" + stripJID(jid) + ".jpg");

        if (file.exists()) return Drawable.createFromPath(file.getAbsolutePath());

        return null;
    }

    public static String getMyName() {
        var startup_prefs = ((Context) mainActivity).getSharedPreferences("startup_prefs", Context.MODE_PRIVATE);
        return startup_prefs.getString("push_name", "WhatsApp");
    }

    public static String getMyNumber() {
        var mainPrefs = Utils.getApplication().getSharedPreferences(Utils.getApplication().getPackageName() + "_preferences_light", Context.MODE_PRIVATE);
        return mainPrefs.getString("registration_jid", "");
    }

    public static String getMyBio() {
        var mainPrefs = Utils.getApplication().getSharedPreferences(Utils.getApplication().getPackageName() + "_preferences_light", Context.MODE_PRIVATE);
        return mainPrefs.getString("my_current_status", "");
    }

    public static Drawable getMyPhoto() {
        String datafolder = Utils.getApplication().getCacheDir().getParent() + "/";
        File file = new File(datafolder + "files" + "/" + "me.jpg");
        if (file.exists()) return Drawable.createFromPath(file.getAbsolutePath());
        return null;
    }

    public static Activity getMainActivity() {
        return (Activity) mainActivity;
    }


    public static Dialog createDialog(Context context) {
        return (Dialog) XposedHelpers.newInstance(bottomDialog, context, 0);
    }

    public static Activity getCurrenConversation() {
        return mConversation;
    }

    public static void addListenerChat(ObjectOnChangeListener listener) {
        listenerChat.add(listener);
    }

}
