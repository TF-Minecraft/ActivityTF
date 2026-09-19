package tfmc.justin.activity.listeners;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// ====================================
// The ic_chat anti-farm decision. The event itself needs a server, so this
// drives the static helpers with plain strings.
// ====================================
class CharacterChatListenerTest {

    @Test
    void fifteenCharactersOrFewerNeverCount() {
        assertFalse(CharacterChatListener.counts("123456789012345", null));
        assertFalse(CharacterChatListener.counts("hi", null));
    }

    @Test
    void sixteenCharactersCount() {
        assertTrue(CharacterChatListener.counts("1234567890123456", null));
    }

    @Test
    void colourCodesAndPaddingDoNotAddLength() {
        String text = CharacterChatListener.strip("  &aHello §bthere &#FF00FFfriend  ");
        assertEquals("Hello there friend", text);
        assertFalse(CharacterChatListener.counts(CharacterChatListener.strip("&a&b&c&d&e&fshort"), null));
    }

    // Every step compared against the one before it, as the listener does
    @Test
    void aChainOfSuffixVariationsIsRejected() {
        String first = "blablablablablablabla";
        assertTrue(CharacterChatListener.counts(first, null));
        assertFalse(CharacterChatListener.counts(first + "1", first));
        assertFalse(CharacterChatListener.counts(first + "2", first + "1"));
        assertFalse(CharacterChatListener.counts(first + " more", first + "2"));
    }

    @Test
    void caseAndPunctuationVariantsAreRejected() {
        String previous = "I walk into the tavern.";
        assertFalse(CharacterChatListener.counts("i WALK into the tavern!!!", previous));
        assertFalse(CharacterChatListener.counts("I walk, into the tavern", previous));
    }

    @Test
    void aGenuinelyDifferentMessageCounts() {
        assertTrue(CharacterChatListener.counts("Then I order a drink.", "I walk into the tavern."));
    }
}
