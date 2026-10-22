package io.github.anilken.wynnlabs.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The quest guide's template cleanup on lines from real wiki pages. */
class GuideTemplatesTest {

    @Test
    void colouredTextKeepsItsText() {
        assertEquals("* You should head back to the mage with the wand.",
                GuideScreen.templates("* {{c|#aaa|You should head back to the mage with the wand.}}"));
    }

    @Test
    void nestedTemplatesComeApart() {
        assertEquals("One of the staves is on display in the Nesaak bank. Could you retrieve it?",
                GuideScreen.templates("One of the staves is {{c|var(--color-wc-aqua)|on display in the Nesaak bank.}} "
                        + "Could you retrieve it?{{Clear}}"));
        assertEquals("", GuideScreen.templates("{{Dialogue|green|Telvu|{{c|var(--color-wc-aqua)|Just step into the portal.}}}}"));
    }
}
