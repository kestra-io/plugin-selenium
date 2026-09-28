package io.kestra.plugin.selenium;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.hamcrest.Matchers.containsString;

class AbstractSeleniumTaskTest {

    @Test
    void givenValidHttpUrl_whenParsingGridUri_thenReturnsUri() {
        var uri = AbstractSeleniumTask.parseGridUri("http://localhost:4444");

        assertThat(uri.getScheme(), is("http"));
    }

    @Test
    void givenMalformedUrl_whenParsingGridUri_thenFailsWithClearMessage() {
        var e = assertThrows(IllegalArgumentException.class, () -> AbstractSeleniumTask.parseGridUri("not a url"));

        assertThat(e.getMessage(), containsString("Invalid remoteUrl"));
    }

    @Test
    void givenNonHttpScheme_whenParsingGridUri_thenFailsNamingScheme() {
        var e = assertThrows(IllegalArgumentException.class, () -> AbstractSeleniumTask.parseGridUri("ftp://localhost:4444"));

        assertThat(e.getMessage(), containsString("scheme must be http or https"));
    }

    @Test
    void givenMalformedUrlWithCredentials_whenParsingGridUri_thenMessageDoesNotLeakThem() {
        var e = assertThrows(IllegalArgumentException.class, () -> AbstractSeleniumTask.parseGridUri("not a url user:s3cr3t@host"));

        assertThat(e.getMessage(), not(containsString("s3cr3t")));
    }

    @Test
    void givenNonHttpSchemeWithCredentials_whenParsingGridUri_thenMessageDoesNotLeakThem() {
        var e = assertThrows(IllegalArgumentException.class, () -> AbstractSeleniumTask.parseGridUri("ftp://user:s3cr3t@localhost:4444"));

        assertThat(e.getMessage(), not(containsString("s3cr3t")));
    }
}
