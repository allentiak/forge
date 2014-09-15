/*
 * Forge: Play Magic: the Gathering.
 * Copyright (C) 2011  Forge Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package forge.limited;

import com.google.common.base.Supplier;

import forge.card.CardEdition;
import forge.card.IUnOpenedProduct;
import forge.card.UnOpenedProduct;
import forge.deck.CardPool;
import forge.deck.Deck;
import forge.game.card.Card;
import forge.interfaces.IGuiBase;
import forge.item.IPaperCard;
import forge.item.PaperCard;
import forge.item.SealedProduct;
import forge.model.CardBlock;
import forge.model.FModel;
import forge.properties.ForgePreferences;
import forge.properties.ForgeConstants;
import forge.util.FileUtil;
import forge.util.HttpUtil;
import forge.util.ItemPool;
import forge.util.gui.SGuiChoose;
import forge.util.gui.SOptionPane;
import forge.util.storage.IStorage;

import org.apache.commons.lang3.ArrayUtils;

import java.io.File;
import java.util.*;
import java.util.Map.Entry;

/**
 * 
 * Booster Draft Format.
 * 
 */
public class BoosterDraft implements IBoosterDraft {
    private final BoosterDraftAI draftAI = new BoosterDraftAI();
    private static final int N_PLAYERS = 8;
    public static final String FILE_EXT = ".draft";

    protected int nextBoosterGroup = 0;
    private int currentBoosterSize = 0;
    private int currentBoosterPick = 0;
    private List<List<PaperCard>> pack; // size 8

    /** The draft picks. */
    private final Map<String, Float> draftPicks = new TreeMap<String, Float>();
    protected LimitedPoolType draftFormat;

    protected final List<Supplier<List<PaperCard>>> product = new ArrayList<Supplier<List<PaperCard>>>();

    public static BoosterDraft createDraft(final IGuiBase gui, final LimitedPoolType draftType) {
        BoosterDraft draft = new BoosterDraft(draftType);
        if (!draft.generateProduct(gui)) { return null; }
        return draft;
    }

    protected boolean generateProduct(final IGuiBase gui) {
        switch (this.draftFormat) {
        case Full: // Draft from all cards in Forge
            Supplier<List<PaperCard>> s = new UnOpenedProduct(SealedProduct.Template.genericBooster);

            for (int i = 0; i < 3; i++) {
                this.product.add(s);
            }
            IBoosterDraft.LAND_SET_CODE[0] = CardEdition.Predicates.getRandomSetWithAllBasicLands(FModel.getMagicDb().getEditions());
            break;

        case Block: // Draft from cards by block or set
        case FantasyBlock:
            List<CardBlock> blocks = new ArrayList<CardBlock>();
            IStorage<CardBlock> storage = this.draftFormat == LimitedPoolType.Block
                    ? FModel.getBlocks() : FModel.getFantasyBlocks();

            for (CardBlock b : storage) {
                if (b.getCntBoostersDraft() > 0) {
                    blocks.add(b);
                }
            }

            final CardBlock block = SGuiChoose.oneOrNone(gui, "Choose Block", blocks);
            if (block == null) { return false; }

            final CardEdition[] cardSets = block.getSets();
            if (cardSets.length == 0) {
                SOptionPane.showErrorDialog(gui, block.toString() + " does not contain any set combinations.");
                return false;
            }

            final Stack<String> sets = new Stack<String>();
            for (int k = cardSets.length - 1; k >= 0; k--) {
                sets.add(cardSets[k].getCode());
            }

            for (String setCode : block.getMetaSetNames()) {
                if (block.getMetaSet(setCode).isDraftable()) {
                    sets.push(setCode); // to the beginning
                }
            }

            final int nPacks = block.getCntBoostersDraft();

            if (sets.size() > 1) {
                final Object p = SGuiChoose.oneOrNone(gui, "Choose Set Combination", getSetCombos(sets));
                if (p == null) { return false; }

                final String[] pp = p.toString().split("/");
                for (int i = 0; i < nPacks; i++) {
                    this.product.add(block.getBooster(pp[i], gui));
                }
            }
            else {
                IUnOpenedProduct product1 = block.getBooster(sets.get(0), gui);

                for (int i = 0; i < nPacks; i++) {
                    this.product.add(product1);
                }
            }

            IBoosterDraft.LAND_SET_CODE[0] = block.getLandSet();
            break;

        case Custom:
            final List<CustomLimited> myDrafts = this.loadCustomDrafts();

            if (myDrafts.isEmpty()) {
                SOptionPane.showMessageDialog(gui, "No custom draft files found.");
            }
            else {
                final CustomLimited customDraft = SGuiChoose.oneOrNone(gui, "Choose Custom Draft", myDrafts);
                if (customDraft == null) { return false; }

                this.setupCustomDraft(customDraft);
            }
            break;

        default:
            throw new NoSuchElementException("Draft for mode " + this.draftFormat + " has not been set up!");
        }

        this.pack = this.get8BoosterPack();
        return true;
    }

    public static BoosterDraft createDraft(final IGuiBase gui, final LimitedPoolType draftType, final CardBlock block, final String[] boosters) {   
        BoosterDraft draft = new BoosterDraft(draftType);
        
        final int nPacks = boosters.length;

        for (int i = 0; i < nPacks; i++) {
            draft.product.add(block.getBooster(boosters[i], gui));
        }

        IBoosterDraft.LAND_SET_CODE[0] = block.getLandSet();

        draft.pack = draft.get8BoosterPack();
        return draft;
    }

    protected BoosterDraft() {
        this.draftFormat = LimitedPoolType.Full;
    }
    /**
     * <p>
     * Constructor for BoosterDraft_1.
     * </p>
     *
     * @param draftType
     *            a {@link java.lang.String} object.
     */
    protected BoosterDraft(final LimitedPoolType draftType) {
        this.draftAI.setBd(this);
        this.draftFormat = draftType;
    }

    private void setupCustomDraft(final CustomLimited draft) {
        final ItemPool<PaperCard> dPool = draft.getCardPool();
        if (dPool == null) {
            throw new RuntimeException("BoosterGenerator : deck not found");
        }

        final SealedProduct.Template tpl = draft.getSealedProductTemplate();

        UnOpenedProduct toAdd = new UnOpenedProduct(tpl, dPool);
        toAdd.setLimitedPool(draft.isSingleton());
        for (int i = 0; i < draft.getNumPacks(); i++) {
            this.product.add(toAdd);
        }

        IBoosterDraft.LAND_SET_CODE[0] = FModel.getMagicDb().getEditions().get(draft.getLandSetCode());
    }

    /** Looks for draft files, reads them, returns a list. */
    private List<CustomLimited> loadCustomDrafts() {
        String[] dList;
        final ArrayList<CustomLimited> customs = new ArrayList<CustomLimited>();

        // get list of custom draft files
        final File dFolder = new File(ForgeConstants.DRAFT_DIR);
        if (!dFolder.exists()) {
            throw new RuntimeException("BoosterDraft : folder not found -- folder is " + dFolder.getAbsolutePath());
        }

        if (!dFolder.isDirectory()) {
            throw new RuntimeException("BoosterDraft : not a folder -- " + dFolder.getAbsolutePath());
        }

        dList = dFolder.list();

        for (final String element : dList) {
            if (element.endsWith(FILE_EXT)) {
                final List<String> dfData = FileUtil.readFile(ForgeConstants.DRAFT_DIR + element);
                customs.add(CustomLimited.parse(dfData, FModel.getDecks().getCubes()));
            }
        }
        return customs;
    }

    /**
     * <p>
     * nextChoice.
     * </p>
     * 
     * @return a {@link forge.deck.CardPool} object.
     */
    @Override
    public CardPool nextChoice() {
        if (this.pack.get(this.getCurrentBoosterIndex()).size() == 0) {
            this.pack = this.get8BoosterPack();
        }

        this.computerChoose();
        CardPool result = new CardPool();
        result.addAllFlat(this.pack.get(this.getCurrentBoosterIndex()));
        return result;
    }

    /**
     * <p>
     * get8BoosterPack.
     * </p>
     * 
     * @return an array of {@link forge.deck.CardPool} objects.
     */
    public List<List<PaperCard>> get8BoosterPack() {
        if (this.nextBoosterGroup >= this.product.size()) {
            return null;
        }

        final List<List<PaperCard>> list = new ArrayList<List<PaperCard>>();
        for (int i = 0; i < 8; i++) {
            list.add(this.product.get(this.nextBoosterGroup).get());
        }

        this.nextBoosterGroup++;
        this.currentBoosterSize = list.get(0).size();
        this.currentBoosterPick = 0;
        return list;
    }

    // size 7, all the computers decks

    /**
     * <p>
     * getDecks.
     * </p>
     * 
     * @return an array of {@link forge.deck.Deck} objects.
     */
    @Override
    public Deck[] getDecks() {
        return this.draftAI.getDecks();
    }

    protected void computerChoose() {
        final int iHumansBooster = this.getCurrentBoosterIndex();
        int iPlayer = 0;
        for (int i = 1; i < this.pack.size(); i++) {
            final List<Card> forAi = new ArrayList<Card>();
            final List<PaperCard> booster = this.pack.get((iHumansBooster + i) % this.pack.size());
            for (final IPaperCard cr : booster) {
                forAi.add(Card.getCardForUi(cr)); // ai is supposed to analyze it only
            }

            final PaperCard aiPick = this.draftAI.choose(booster, iPlayer++);
            booster.remove(aiPick);
        }
    } // computerChoose()

    /**
     * 
     * Get the current booster index.
     * @return int
     */
    public int getCurrentBoosterIndex() {
        return this.currentBoosterPick % BoosterDraft.N_PLAYERS;
    }

    /**
     * <p>
     * hasNextChoice.
     * </p>
     * 
     * @return a boolean.
     */
    @Override
    public boolean hasNextChoice() {
        final boolean isLastGroup = this.nextBoosterGroup >= this.product.size();
        final boolean isBoosterDepleted = this.currentBoosterPick >= this.currentBoosterSize;
        final boolean noMoreCards = isLastGroup && isBoosterDepleted;
        return !noMoreCards;
    }

    /** {@inheritDoc} */
    @Override
    public void setChoice(final PaperCard c) {
        final List<PaperCard> thisBooster = this.pack.get(this.getCurrentBoosterIndex());

        if (!thisBooster.contains(c)) {
            throw new RuntimeException("BoosterDraft : setChoice() error - card not found - " + c
                    + " - booster pack = " + thisBooster);
        }

        if (ForgePreferences.UPLOAD_DRAFT) {
            for (int i = 0; i < thisBooster.size(); i++) {
                final PaperCard cc = thisBooster.get(i);
                final String cnBk = cc.getName() + "|" + cc.getEdition();

                float pickValue = 0;
                if (cc.equals(c)) {
                    pickValue = thisBooster.size()
                            * (1f - (((float) this.currentBoosterPick / this.currentBoosterSize) * 2f));
                }
                else {
                    pickValue = 0;
                }

                if (!this.draftPicks.containsKey(cnBk)) {
                    this.draftPicks.put(cnBk, pickValue);
                }
                else {
                    final float curValue = this.draftPicks.get(cnBk);
                    final float newValue = (curValue + pickValue) / 2;
                    this.draftPicks.put(cnBk, newValue);
                }
            }
        }

        thisBooster.remove(c);
        this.currentBoosterPick++;
    } // setChoice()

    /** This will upload drafting picks to cardforge HQ. */
    @Override
    public void finishedDrafting() {
        if (!ForgePreferences.UPLOAD_DRAFT || 1 >= draftPicks.size()) {
            return;
        }

        ArrayList<String> outDraftData = new ArrayList<String>();
        for (Entry<String, Float> key : draftPicks.entrySet()) {
            outDraftData.add(key.getValue() + "|" + key.getKey());
        }
        Collections.sort(outDraftData);
        HttpUtil.upload(ForgeConstants.URL_DRAFT_UPLOAD + "?fmt=" + draftFormat, outDraftData);
    }

    @Override
    public boolean isPileDraft() {
        return false;
    }

    private static List<String> getSetCombos(final List<String> setz) {
        String[] sets = setz.toArray(ArrayUtils.EMPTY_STRING_ARRAY);
        List<String> setCombos = new ArrayList<String>();
        if (sets.length >= 2) {
            setCombos.add(String.format("%s/%s/%s", sets[0], sets[0], sets[0]));
            setCombos.add(String.format("%s/%s/%s", sets[0], sets[0], sets[1]));
            setCombos.add(String.format("%s/%s/%s", sets[0], sets[1], sets[1]));
            if (sets.length >= 3) {
                setCombos.add(String.format("%s/%s/%s", sets[0], sets[1], sets[2]));
                setCombos.add(String.format("%s/%s/%s", sets[0], sets[2], sets[2]));
            }
            setCombos.add(String.format("%s/%s/%s", sets[1], sets[0], sets[0]));
            setCombos.add(String.format("%s/%s/%s", sets[1], sets[1], sets[0]));
            setCombos.add(String.format("%s/%s/%s", sets[1], sets[1], sets[1]));
            if (sets.length >= 3) {
                setCombos.add(String.format("%s/%s/%s", sets[1], sets[1], sets[2]));
                setCombos.add(String.format("%s/%s/%s", sets[1], sets[2], sets[2]));
            }
        }
        if (sets.length >= 3) {
            setCombos.add(String.format("%s/%s/%s", sets[2], sets[1], sets[0]));
            setCombos.add(String.format("%s/%s/%s", sets[2], sets[2], sets[0]));
            setCombos.add(String.format("%s/%s/%s", sets[2], sets[2], sets[1]));
            setCombos.add(String.format("%s/%s/%s", sets[2], sets[2], sets[2]));
        } // Beyond 3, skimp on the choice configurations, or the list will be enormous!
        if (sets.length >= 4) {
            setCombos.add(String.format("%s/%s/%s", sets[3], sets[1], sets[0]));
            setCombos.add(String.format("%s/%s/%s", sets[3], sets[2], sets[1]));
        }
        if (sets.length >= 5) {
            setCombos.add(String.format("%s/%s/%s", sets[4], sets[1], sets[0]));
            setCombos.add(String.format("%s/%s/%s", sets[4], sets[3], sets[2]));
            setCombos.add(String.format("%s/%s/%s", sets[4], sets[2], sets[0]));
        }
        if (sets.length >= 6) {
            setCombos.add(String.format("%s/%s/%s", sets[5], sets[1], sets[0]));
            setCombos.add(String.format("%s/%s/%s", sets[5], sets[3], sets[2]));
            setCombos.add(String.format("%s/%s/%s", sets[5], sets[4], sets[3]));
            setCombos.add(String.format("%s/%s/%s", sets[5], sets[2], sets[0]));
        }
        if (sets.length >= 7) {
            setCombos.add(String.format("%s/%s/%s", sets[6], sets[1], sets[0]));
            setCombos.add(String.format("%s/%s/%s", sets[6], sets[3], sets[2]));
            setCombos.add(String.format("%s/%s/%s", sets[6], sets[5], sets[4]));
            setCombos.add(String.format("%s/%s/%s", sets[6], sets[3], sets[0]));
        }
        if (sets.length >= 8) {
            setCombos.add(String.format("%s/%s/%s", sets[7], sets[1], sets[0]));
            setCombos.add(String.format("%s/%s/%s", sets[7], sets[3], sets[2]));
            setCombos.add(String.format("%s/%s/%s", sets[7], sets[5], sets[4]));
            setCombos.add(String.format("%s/%s/%s", sets[7], sets[6], sets[5]));
            setCombos.add(String.format("%s/%s/%s", sets[7], sets[3], sets[0]));
        }
        if (sets.length >= 9) {
            setCombos.add(String.format("%s/%s/%s", sets[8], sets[1], sets[0]));
            setCombos.add(String.format("%s/%s/%s", sets[8], sets[3], sets[2]));
            setCombos.add(String.format("%s/%s/%s", sets[8], sets[5], sets[4]));
            setCombos.add(String.format("%s/%s/%s", sets[8], sets[7], sets[6]));
            setCombos.add(String.format("%s/%s/%s", sets[8], sets[4], sets[0]));
        }
        return setCombos;
    }
}
