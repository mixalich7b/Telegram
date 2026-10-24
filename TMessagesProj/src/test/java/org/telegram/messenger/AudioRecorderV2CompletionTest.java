package org.telegram.messenger;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class AudioRecorderV2CompletionTest {
    @Test
    public void scheduledRecordingWaitsForDateAfterEncodingCompletes() {
        List<String> sent = new ArrayList<>();
        AudioRecorderV2Completion<String> completion = new AudioRecorderV2Completion<>(sent::add);
        assertFalse(completion.request(true));
        completion.complete("voice.ogg");
        assertTrue(sent.isEmpty());
        assertTrue(completion.request(false));
        assertEquals(List.of("voice.ogg"), sent);
    }

    @Test
    public void dateChosenBeforeEncodingCompletesStillSendsOnce() {
        List<String> sent = new ArrayList<>();
        AudioRecorderV2Completion<String> completion = new AudioRecorderV2Completion<>(sent::add);
        assertFalse(completion.request(true));
        assertFalse(completion.request(false));
        assertTrue(sent.isEmpty());
        completion.complete("voice.ogg");
        assertEquals(List.of("voice.ogg"), sent);
    }

    @Test
    public void cancelAfterEncodingDoesNotSendOnLateConfirmation() {
        List<String> sent = new ArrayList<>();
        AudioRecorderV2Completion<String> completion = new AudioRecorderV2Completion<>(sent::add);
        completion.request(true);
        completion.complete("voice.ogg");
        completion.cancel();
        assertTrue(completion.request(false));
        assertTrue(sent.isEmpty());
    }

    @Test
    public void cancelBeforeEncodingIgnoresLateCompletion() {
        List<String> sent = new ArrayList<>();
        AudioRecorderV2Completion<String> completion = new AudioRecorderV2Completion<>(sent::add);
        completion.request(true);
        completion.cancel();
        completion.complete("voice.ogg");
        assertTrue(completion.request(false));
        assertTrue(sent.isEmpty());
    }

    @Test
    public void immediateSendAndDuplicateEventsDeliverOnlyOnce() {
        List<String> sent = new ArrayList<>();
        AudioRecorderV2Completion<String> completion = new AudioRecorderV2Completion<>(sent::add);
        assertFalse(completion.request(false));
        completion.complete("voice.ogg");
        completion.complete("duplicate.ogg");
        assertTrue(completion.request(false));
        assertEquals(List.of("voice.ogg"), sent);
    }
}
