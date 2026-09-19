package tfmc.justin.activity.listeners;

import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// ====================================
// The ic_chat anti-farm decision. The event itself needs a server, so this
// drives the static helpers with plain strings, and process() for the
// per-player history rules.
// ====================================
class CharacterChatListenerTest {

    private final CharacterChatListener listener = new CharacterChatListener(null);
    private final UUID player = UUID.randomUUID();

    private boolean say(String message) {
        return listener.process(player, CharacterChatListener.strip(message));
    }

    private static boolean counts(String text, List<String> history) {
        String norm = CharacterChatListener.normalize(text);
        return CharacterChatListener.passesGate(text, norm) && CharacterChatListener.isNovel(norm, history);
    }

    @Test
    void fifteenCharactersOrFewerNeverCount() {
        assertFalse(counts("123456789012345", List.of()));
        assertFalse(counts("hi", List.of()));
    }

    @Test
    void sixteenLettersCount() {
        assertTrue(counts("abcdefghijklmnop", List.of()));
        assertTrue(counts("abcdefghijklmnop",
                List.of(CharacterChatListener.normalize("The rain keeps falling on the roof"))));
    }

    @Test
    void messagesWithoutLettersNeverCount() {
        assertFalse(say("..................."));
        assertFalse(say("!!!??? ...,,, ---"));
    }

    @Test
    void digitOnlyMessagesNeverCountOrEnterHistory() {
        assertFalse(say("1234567890123456789"));
        assertEquals(0, listener.historySize(player));
    }

    @Test
    void paddedFewCharacterMessagesNeverCount() {
        assertFalse(say("...............a"));
        assertFalse(say("...............b"));
        assertFalse(say("hi.............."));
        assertFalse(say("yo.............."));
        assertEquals(0, listener.historySize(player));
    }

    @Test
    void nineNormalizedCharactersIsBelowTheGate() {
        assertFalse(say("abcdefghi......."));
        assertTrue(say("abcdefghij......"));
    }

    // Digits are dropped by normalization, so number-only differences are repeats (accepted)
    @Test
    void messagesDifferingOnlyInNumbersAreRepeats() {
        assertTrue(say("I hand him 30 silver coins"));
        assertFalse(say("I hand him 50 silver coins"));
    }

    @Test
    void colourCodesAndPaddingDoNotAddLength() {
        String text = CharacterChatListener.strip("  &aHello §bthere &#FF00FFfriend  ");
        assertEquals("Hello there friend", text);
        assertFalse(counts(CharacterChatListener.strip("&a&b&c&d&e&fshort"), List.of()));
    }

    @Test
    void levenshteinDistance() {
        assertEquals(3, CharacterChatListener.distance("kitten", "sitting"));
        assertEquals(0, CharacterChatListener.distance("same", "same"));
        assertEquals(4, CharacterChatListener.distance("", "abcd"));
    }

    @Test
    void anUnrelatedMessageDoesNotBlockAShortLookingOne() {
        assertTrue(say("The rain keeps falling on the roof"));
        assertTrue(say("I look at the broken door"));
    }

    @Test
    void shortMessagesDoNotBlockWhatFollows() {
        assertFalse(say("ok"));
        assertTrue(say("I look at the broken door"));
        assertFalse(say("*nods*"));
        assertTrue(say("He nods slowly and leaves the room"));
        assertFalse(say("..."));
        assertFalse(say(":)"));
        assertTrue(say("I walk into the tavern and sit down"));
    }

    @Test
    void aChainOfSuffixVariationsIsRejected() {
        assertTrue(say("I walk into the tavern slowly"));
        assertFalse(say("I walk into the tavern slowly1"));
        assertFalse(say("I walk into the tavern slowly2"));
    }

    @Test
    void oneLetterSuffixVariantsAreRejected() {
        assertTrue(say("I walk quietly into the tavern a"));
        assertFalse(say("I walk quietly into the tavern b"));
    }

    @Test
    void oneLetterPrefixVariantsAreRejected() {
        assertTrue(say("a I walk quietly into the tavern"));
        assertFalse(say("b I walk quietly into the tavern"));
    }

    @Test
    void selfRepeatingMessagesNeverCountOrEnterHistory() {
        assertFalse(say("aaaaaaaaaaaaaaaa"));
        assertFalse(say("bbbbbbbbbbbbbbbb"));
        assertFalse(say("hahahahahahahahaha"));
        assertFalse(say("hehehehehehehehehe"));
        assertFalse(say("blablablablablabla"));
        assertFalse(say("I walk into the tavern I walk into the tavern"));
        assertEquals(0, listener.historySize(player));
    }

    @Test
    void genuineVariedLetterCountingStillCounts() {
        assertTrue(say("No, no, no, I told you to wait outside"));
    }

    @Test
    void oneLetterSubstitutionIsRejected() {
        assertTrue(say("I walk into the tavern and sit down"));
        assertFalse(say("I walk into the tavern and sat down"));
    }

    @Test
    void caseAndPunctuationVariantsAreRejected() {
        assertTrue(say("I walk into the tavern."));
        assertFalse(say("i WALK into the tavern!!!"));
        assertFalse(say("I walk, into the tavern"));
    }

    @Test
    void alternatingTwoLinesOnlyCountsEachOnce() {
        String a = "I walk into the tavern and sit down";
        String b = "The barkeep pours me a mug of ale";
        assertTrue(say(a));
        assertTrue(say(b));
        assertFalse(say(a));
        assertFalse(say(b));
        assertFalse(say(a));
        assertFalse(say(b));
    }

    @Test
    void shortFillerDoesNotResetTheHistory() {
        assertTrue(say("same long message here"));
        assertFalse(say("zq"));
        assertFalse(say("same long message here"));
    }

    @Test
    void genuinelyDifferentSentencesAllCount() {
        assertTrue(say("I walk into the tavern and sit down"));
        assertTrue(say("The barkeep pours me a mug of ale"));
        assertTrue(say("I thank him and toss a silver coin on the bar"));
        assertTrue(say("A hooded stranger watches me from the corner"));
        assertTrue(say("I raise my mug toward him in greeting"));
        assertTrue(say("He stands and slowly walks over to my table"));
        assertTrue(say("Then I order a drink."));
    }

    @Test
    void cyclingSixCannedLinesOnlyCountsEachOnce() {
        cycle(6);
    }

    @Test
    void cyclingTwentyLinesOnlyCountsEachOnce() {
        cycle(CharacterChatListener.HISTORY);
    }

    // n distinct lines, then the same n again: the repeats never count
    private void cycle(int n) {
        for (int i = 0; i < n; i++) {
            assertTrue(say(line(i)), "first " + i);
        }
        for (int i = 0; i < n; i++) {
            assertFalse(say(line(i)), "repeat " + i);
        }
    }

    // Distinct lines whose normalized forms are far apart
    private static String line(int i) {
        String[] words = {"tavern", "silver", "hooded", "barkeep", "stranger",
                "window", "candle", "forest", "river", "mountain", "castle",
                "dragon", "market", "harbor", "shield", "lantern", "meadow",
                "temple", "bridge", "garden", "orchard", "chapel", "quarry",
                "cellar", "beacon", "hamlet", "prairie", "thicket", "citadel",
                "wharf", "abbey"};
        return "I look at the " + words[i] + " " + words[(i * 7 + 3) % words.length]
                + " " + words[(i * 11 + 5) % words.length];
    }

    @Test
    void similarityThreshold() {
        String fifty = "a".repeat(50);
        // exactly 0.8 is too similar
        assertTrue(CharacterChatListener.similar(fifty, "a".repeat(40) + "b".repeat(10)));
        // 0.78 is not
        assertFalse(CharacterChatListener.similar(fifty, "a".repeat(39) + "b".repeat(11)));
        // length-difference shortcut sits on the same boundary
        assertTrue(CharacterChatListener.similar(fifty, "a".repeat(40)));
        assertFalse(CharacterChatListener.similar(fifty, "a".repeat(39)));
    }

    @Test
    void historyIsCappedOldestEvicted() {
        for (int i = 0; i <= CharacterChatListener.HISTORY; i++) {
            assertTrue(say(line(i)));
        }
        assertEquals(CharacterChatListener.HISTORY, listener.historySize(player));
        // line 1 is still remembered, line 0 scrolled out
        assertFalse(say(line(1)));
        assertTrue(say(line(0)));
    }

    // Relogging must not clear the history, so nothing listens for quits
    @Test
    void historyPersistsWithoutQuitHandling() {
        for (Method m : CharacterChatListener.class.getDeclaredMethods()) {
            for (Class<?> p : m.getParameterTypes()) {
                assertFalse(PlayerQuitEvent.class.isAssignableFrom(p), m.getName());
            }
        }
        assertTrue(say("I walk into the tavern and sit down"));
        assertFalse(say("I walk into the tavern and sit down"));
    }

    @Test
    void historyIsPerPlayer() {
        assertTrue(say("I walk into the tavern and sit down"));
        assertTrue(listener.process(UUID.randomUUID(), "I walk into the tavern and sit down"));
    }
}
