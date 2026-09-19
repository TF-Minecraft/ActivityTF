package tfmc.justin.activity.listeners;

import org.junit.jupiter.api.Test;

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

    @Test
    void fifteenCharactersOrFewerNeverCount() {
        assertFalse(CharacterChatListener.counts("123456789012345", List.of()));
        assertFalse(CharacterChatListener.counts("hi", List.of()));
    }

    @Test
    void sixteenLettersCount() {
        assertTrue(CharacterChatListener.counts("abcdefghijklmnop", List.of()));
        assertTrue(CharacterChatListener.counts("abcdefghijklmnop",
                List.of(CharacterChatListener.letters("The rain keeps falling on the roof"))));
    }

    @Test
    void messagesWithoutLettersNeverCount() {
        assertFalse(say("1234567890123456789"));
        assertFalse(say("..................."));
    }

    @Test
    void colourCodesAndPaddingDoNotAddLength() {
        String text = CharacterChatListener.strip("  &aHello §bthere &#FF00FFfriend  ");
        assertEquals("Hello there friend", text);
        assertFalse(CharacterChatListener.counts(CharacterChatListener.strip("&a&b&c&d&e&fshort"), List.of()));
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
        assertTrue(say("blablablablablabla"));
        assertFalse(say("blablablablablabla1"));
        assertFalse(say("blablablablablabla2"));
    }

    @Test
    void oneLetterSuffixVariantsAreRejected() {
        assertTrue(say("blablablablablabla a"));
        assertFalse(say("blablablablablabla b"));
    }

    @Test
    void oneLetterPrefixVariantsAreRejected() {
        assertTrue(say("a blablablablablabla"));
        assertFalse(say("b blablablablablabla"));
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
    void historyIsPerPlayer() {
        assertTrue(say("I walk into the tavern and sit down"));
        assertTrue(listener.process(UUID.randomUUID(), "I walk into the tavern and sit down"));
    }
}
