package com.cpq.quotation.card;

import com.cpq.quotation.service.card.CardEffectiveRows;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * repair-260918 B-1: the single "card values usable" predicate
 * ({@link CardEffectiveRows#isCardValuesUsable}). Pure unit test, no DB.
 *
 * <p>Unusable = null / blank; not parseable JSON (or root not an object);
 * {@code "__cardValueFailed": true}; {@code tabs} missing, not an array, or empty.
 * Blank and malformed JSON cannot be stored in the jsonb column, so they are only covered here.
 */
class CardValuesUsableTest {

    @Test
    void nullOrBlank_isUnusable() {
        assertFalse(CardEffectiveRows.isCardValuesUsable(null));
        assertFalse(CardEffectiveRows.isCardValuesUsable(""));
        assertFalse(CardEffectiveRows.isCardValuesUsable("   "));
    }

    @Test
    void malformedJsonOrNonObjectRoot_isUnusable() {
        assertFalse(CardEffectiveRows.isCardValuesUsable("{\"tabs\":[{\"componentId\":\"x\""));
        assertFalse(CardEffectiveRows.isCardValuesUsable("not json"));
        assertFalse(CardEffectiveRows.isCardValuesUsable("\"just a string\""));
        assertFalse(CardEffectiveRows.isCardValuesUsable("[{\"componentId\":\"x\"}]"));
    }

    @Test
    void failedSentinel_isUnusable() {
        // exact production sentinel (CardSnapshotService.CARD_VALUE_FAILED_SENTINEL)
        assertFalse(CardEffectiveRows.isCardValuesUsable("{\"tabs\":[],\"__cardValueFailed\":true}"));
        // sentinel flag wins even when tabs are present
        assertFalse(CardEffectiveRows.isCardValuesUsable(
            "{\"tabs\":[{\"componentId\":\"x\",\"subtotal\":\"1\"}],\"__cardValueFailed\":true,\"error\":\"boom\"}"));
    }

    @Test
    void tabsMissingNotArrayOrEmpty_isUnusable() {
        assertFalse(CardEffectiveRows.isCardValuesUsable("{}"));
        assertFalse(CardEffectiveRows.isCardValuesUsable("{\"tabs\":null}"));
        assertFalse(CardEffectiveRows.isCardValuesUsable("{\"tabs\":{\"componentId\":\"x\"}}"));
        assertFalse(CardEffectiveRows.isCardValuesUsable("{\"tabs\":[]}"));
    }

    @Test
    void normalCardValues_areUsable() {
        assertTrue(CardEffectiveRows.isCardValuesUsable(
            "{\"tabs\":[{\"tabName\":\"物料\",\"componentId\":\"b3445979-e3d6-4cdb-ab2d-53758b5f2eb2\","
                + "\"componentType\":\"NORMAL\",\"baseRows\":[],\"editRows\":[],\"formulaResults\":[],"
                + "\"resolvedRows\":[],\"subtotal\":\"0\",\"subtotalByColumn\":{}}]}"));
        // __cardValueFailed explicitly false is not the sentinel
        assertTrue(CardEffectiveRows.isCardValuesUsable(
            "{\"tabs\":[{\"componentId\":\"x\",\"subtotal\":\"1\"}],\"__cardValueFailed\":false}"));
    }
}
