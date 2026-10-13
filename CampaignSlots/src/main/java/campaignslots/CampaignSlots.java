package campaignslots;

import arc.*;
import arc.files.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.mod.*;
import mindustry.ui.*;
import mindustry.ui.dialogs.*;

import java.io.*;
import java.text.*;
import java.util.*;
import java.util.regex.*;

import static mindustry.Vars.*;

public class CampaignSlots extends Mod{
    static final int SLOT_COUNT = 5;
    // Settings keys matching this are treated as campaign progress. Edit if something is missed.
    static final Pattern CAMPAIGN_KEY = Pattern.compile("unlock|sector|launch|research|campaign|planet|node|completed|tech|zone|survived|loadout", Pattern.CASE_INSENSITIVE);
    static final String OWN_PREFIX = "campaignslots-";
    static final String SAVE_PREFIX = "sector-";

    public CampaignSlots(){
        Events.on(ClientLoadEvent.class, e -> {
            ui.settings.addCategory("Campaign Slots", t -> {
                t.button("Open Campaign Slots", this::openDialog).size(300f, 60f).pad(10f);
            });
        });
    }

    // ---------- paths ----------
    Fi root(){ return dataDirectory.child("campaign-slots"); }
    Fi slotDir(int i){ return root().child("slot" + i); }
    boolean slotUsed(int i){ return slotDir(i).child("meta.txt").exists(); }

    // ---------- settings snapshot ----------
    boolean isCampaignKey(String k){
        return !k.startsWith(OWN_PREFIX) && CAMPAIGN_KEY.matcher(k).find();
    }

    Seq<String> listKeys(){
        Seq<String> out = new Seq<>();
        for(String k : Core.settings.keys()) out.add(k);
        return out;
    }

    void snapshotSettings(Fi file){
        try(DataOutputStream out = new DataOutputStream(new BufferedOutputStream(file.write(false)))){
            Seq<String> keys = listKeys().select(this::isCampaignKey);
            int count = 0;
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            DataOutputStream body = new DataOutputStream(buf);
            for(String k : keys){
                Object v = Core.settings.get(k, null);
                if(v == null) continue;
                body.writeUTF(k);
                if(v instanceof byte[]){
                    byte[] b = (byte[])v;
                    body.writeUTF("bytes");
                    body.writeInt(b.length);
                    body.write(b);
                }else if(v instanceof Boolean || v instanceof Integer || v instanceof Long || v instanceof Float || v instanceof String){
                    body.writeUTF(v.getClass().getSimpleName());
                    byte[] b = String.valueOf(v).getBytes("UTF-8");
                    body.writeInt(b.length);
                    body.write(b);
                }else{
                    // unsupported type: skip by writing an empty marker
                    body.writeUTF("skip");
                    body.writeInt(0);
                }
                count++;
            }
            out.writeInt(count);
            out.write(buf.toByteArray());
        }catch(IOException ex){
            throw new RuntimeException(ex);
        }
    }

    void restoreSettings(Fi file){
        for(String k : listKeys()) if(isCampaignKey(k)) Core.settings.remove(k);
        try(DataInputStream in = new DataInputStream(new BufferedInputStream(file.read()))){
            int count = in.readInt();
            for(int n = 0; n < count; n++){
                String key = in.readUTF();
                String type = in.readUTF();
                byte[] b = new byte[in.readInt()];
                in.readFully(b);
                String s = new String(b, "UTF-8");
                switch(type){
                    case "bytes": Core.settings.put(key, b); break;
                    case "Boolean": Core.settings.put(key, Boolean.valueOf(s)); break;
                    case "Integer": Core.settings.put(key, Integer.valueOf(s)); break;
                    case "Long": Core.settings.put(key, Long.valueOf(s)); break;
                    case "Float": Core.settings.put(key, Float.valueOf(s)); break;
                    case "String": Core.settings.put(key, s); break;
                    default: break;
                }
            }
        }catch(IOException ex){
            throw new RuntimeException(ex);
        }
    }

    // ---------- save files ----------
    Seq<Fi> campaignSaves(Fi dir){
        Seq<Fi> res = new Seq<>();
        if(!dir.exists()) return res;
        for(Fi f : dir.list()) if(f.name().startsWith(SAVE_PREFIX)) res.add(f);
        return res;
    }

    void saveToSlot(int i){
        Fi dir = slotDir(i);
        if(dir.exists()) dir.deleteDirectory();
        Fi saves = dir.child("saves");
        saves.mkdirs();
        for(Fi f : campaignSaves(saveDirectory)) f.copyTo(saves);
        snapshotSettings(dir.child("settings.bin"));
        dir.child("meta.txt").writeString(new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date()));
    }

    String slotInfo(int i){
        if(!slotUsed(i)) return "[gray]empty[]";
        int n = campaignSaves(slotDir(i).child("saves")).size;
        return n + " sector save(s), " + slotDir(i).child("meta.txt").readString();
    }

    int active(){ return Core.settings.getInt(OWN_PREFIX + "active", 0); }

    void loadSlot(int i){
        int act = active();
        if(act > 0 && act != i) saveToSlot(act); // auto-save current slot before leaving it

        Fi dir = slotDir(i);
        for(Fi f : campaignSaves(saveDirectory)) f.delete();
        for(Fi f : campaignSaves(dir.child("saves"))) f.copyTo(saveDirectory);
        restoreSettings(dir.child("settings.bin"));
        Core.settings.put(OWN_PREFIX + "active", i);
        Core.settings.forceSave();
        closeGame("Slot " + i + " loaded.");
    }

    void newBlankSlot(int i){
        int act = active();
        if(act > 0) saveToSlot(act);
        for(Fi f : campaignSaves(saveDirectory)) f.delete();
        for(String k : listKeys()) if(isCampaignKey(k)) Core.settings.remove(k);
        saveToSlot(i);
        Core.settings.put(OWN_PREFIX + "active", i);
        Core.settings.forceSave();
        closeGame("Fresh campaign started in slot " + i + ".");
    }

    void closeGame(String msg){
        ui.showInfo(msg + " The game will now close - relaunch it to continue.");
        Timer.schedule(() -> Core.app.exit(), 2.5f);
    }

    boolean guard(){
        if(state.isGame()){
            ui.showInfo("Return to the main menu first.");
            return false;
        }
        return true;
    }

    // ---------- UI ----------
    void openDialog(){
        BaseDialog d = new BaseDialog("Campaign Slots");
        d.addCloseButton();
        d.shown(() -> build(d));
        d.show();
    }

    void build(BaseDialog d){
        d.cont.clear();
        int act = active();
        d.cont.add("Active slot: " + (act > 0 ? String.valueOf(act) : "none (not tracked yet)")).row();
        d.cont.add("[lightgray]Use only from the main menu. Loading/new closes the game.[]").padBottom(10f).row();

        for(int n = 1; n <= SLOT_COUNT; n++){
            final int i = n;
            Table t = new Table();
            t.background(Styles.black6);
            t.add((i == act ? "[accent]" : "") + "Slot " + i + "[]\n" + slotInfo(i)).width(260f).left().pad(8f);

            t.button("Save here", () -> {
                if(!guard()) return;
                Runnable go = () -> {
                    saveToSlot(i);
                    Core.settings.put(OWN_PREFIX + "active", i);
                    Core.settings.forceSave();
                    build(d);
                };
                if(slotUsed(i)) ui.showConfirm("Overwrite", "Overwrite slot " + i + " with your current campaign?", go);
                else go.run();
            }).size(120f, 50f).pad(4f);

            t.button("Load", () -> {
                if(!guard()) return;
                if(!slotUsed(i)){ ui.showInfo("Slot is empty."); return; }
                ui.showConfirm("Load", "Load slot " + i + "? Current slot is auto-saved first.", () -> loadSlot(i));
            }).size(100f, 50f).pad(4f);

            t.button("New", () -> {
                if(!guard()) return;
                ui.showConfirm("New campaign", "Wipe current campaign and start fresh in slot " + i + "? (Current slot is auto-saved if tracked. On first use, press 'Save here' on a slot first!)", () -> newBlankSlot(i));
            }).size(90f, 50f).pad(4f);

            t.button("Del", () -> {
                if(!slotUsed(i)) return;
                ui.showConfirm("Delete", "Delete slot " + i + "?", () -> {
                    slotDir(i).deleteDirectory();
                    build(d);
                });
            }).size(70f, 50f).pad(4f);

            d.cont.add(t).row();
        }
    }
}
