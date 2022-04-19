package forge.adventure.util;

import com.badlogic.gdx.scenes.scene2d.ui.*;
import com.badlogic.gdx.utils.Array;
import forge.Forge;
import forge.adventure.data.DialogData;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.stage.MapStage;
import forge.util.Localizer;

/**
 * MapDialog
 * Implements a dialogue/event tree for dialogs.
 */

public class MapDialog {
    private final MapStage stage;
    private Array<DialogData> data;
    private final int parentID;
    private final float WIDTH = 260f;
    static private final String defaultJSON = "[\n" +
            "  {\n" +
            "    \"effect\":[],\n" +
            "    \"name\":\"Error\",\n" +
            "    \"text\":\"This is a fallback dialog.\\nPlease check Forge logs for errors.\",\n" +
            "    \"condition\":[],\n" +
            "    \"options\":[\n" +
            "        { \"name\":\"OK\" }\n" +
            "    ]\n" +
            "  }\n" +
            "]";


    public MapDialog(String S, MapStage stage, int parentID) {
        this.stage = stage;
        this.parentID = parentID;
        if (S.isEmpty()) {
            System.err.print("Dialog error. Dialog property is empty.\n");
            this.data = JSONStringLoader.parse(Array.class, DialogData.class, defaultJSON, defaultJSON);
            return;
        }
        this.data = JSONStringLoader.parse(Array.class, DialogData.class, S, defaultJSON);

    }

    private void loadDialog(DialogData dialog) { //Displays a dialog with dialogue and possible choices.
        setEffects(dialog.effect);
        Dialog D = stage.getDialog();
        Localizer L = Forge.getLocalizer();
        D.getContentTable().clear(); D.getButtonTable().clear(); //Clear tables to start fresh.
        String text; //Check for localized string (locname), otherwise print text.
        if(dialog.loctext != null && !dialog.loctext.isEmpty()) text = L.getMessage(dialog.loctext);
        else text = dialog.text;
        Label A = Controls.newLabel(text);
        A.setWrap(true);
        D.getContentTable().add(A).width(WIDTH); //Add() returns a Cell, which is what the width is being applied to.
        if(dialog.options != null) {
            for(DialogData option:dialog.options) {
                if( isConditionOk(option.condition) ) {
                    String name; //Get localized label if present.
                    if(option.locname != null && !option.locname.isEmpty()) name = L.getMessage(option.locname);
                    else name = option.name;
                    TextButton B = Controls.newTextButton(name,() -> loadDialog(option));
                    B.getLabel().setWrap(true); //We want this to wrap in case it's a wordy choice.
                    D.getButtonTable().add(B).width(WIDTH - 10); //The button table also returns a Cell when adding.
                    //TODO: Reducing the space a tiny bit could help. But should be fine as long as there aren't more than 4-5 options.
                    D.getButtonTable().row(); //Add a row. Tried to allow a few per row but it was a bit erratic.
                }
            }
            stage.showDialog();
        }
        else {
            stage.hideDialog();
        }
    }

    public void activate() {
        for(DialogData dialog:data) {
            if(isConditionOk(dialog.condition)) {
                loadDialog(dialog);
            }
        }
    }

    void setEffects(DialogData.ActionData[] data) {
        if(data==null) return;
        for(DialogData.ActionData E:data) {
            if (E.removeItem != null){ //Removes an item from the player's inventory.
                Current.player().removeItem(E.removeItem);
            }
            if (E.addItem != null){ //Gives an item to the player.
                Current.player().addItem(E.addItem);
            }
            if (E.deleteMapObject != 0){ //Removes a dummy object from the map.
                if(E.deleteMapObject < 0) stage.deleteObject(parentID);
                else stage.deleteObject(E.deleteMapObject);
            }
            if (E.battleWithActorID != 0){ //Starts a battle with the given enemy ID.
                if(E.battleWithActorID < 0) stage.beginDuel(stage.getEnemyByID(parentID));
                else stage.beginDuel(stage.getEnemyByID(E.battleWithActorID));
            }
            if (E.giveBlessing != null) { //Gives a blessing for your next battle.
                Current.player().addBlessing(E.giveBlessing);
            }
            if (E.setColorIdentity != null && !E.setColorIdentity.isEmpty()){ //Sets color identity (use sparingly)
                Current.player().setColorIdentity(E.setColorIdentity);
            }
            //Create map object.
        }
    }

    boolean isConditionOk(DialogData.ConditionData[] data) {
        if( data==null ) return true;
        AdventurePlayer player = Current.player();
        for(DialogData.ConditionData condition:data) {
            if(condition.item != null && !condition.item.isEmpty()) { //Check for an item in player's inventory.
                if(!player.hasItem(condition.item)) {
                    if(!condition.not) return false; //Only return on a false.
                } else if(condition.not) return false;
            }
            if(condition.colorIdentity != null && !condition.colorIdentity.isEmpty()) { //Check for player's color ID.
                if(!player.getColorIdentity().equals(condition.colorIdentity.toUpperCase())){
                    if(!condition.not) return false;
                } else if(condition.not) return false;
            }
            if(condition.hasBlessing != null && !condition.hasBlessing.isEmpty()){ //Check for a named blessing.
                if(!player.hasBlessing(condition.hasBlessing)){
                   if(!condition.not) return false;
                } else if(condition.not) return false;
            }
            if(condition.actorID != 0) { //Check for actor ID.
                if(!stage.lookForID(condition.actorID)){
                    if(!condition.not) return false; //Same as above.
                } else if(condition.not) return false;
            }
        }
        return true;
    }
}
