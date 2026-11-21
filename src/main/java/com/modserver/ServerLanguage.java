package com.modserver;

import com.google.gson.JsonParser;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Renders the server UI in the player's language, including for unmodded clients. */
public enum ServerLanguage {
    ENGLISH("en_us"), SPANISH("es_es"), ARGENTINE_SPANISH("es_ar");

    private final Map<String, String> messages;
    private final Locale locale;

    ServerLanguage(String language) {
        locale = Locale.forLanguageTag(language.replace('_', '-'));
        String path = "/assets/modserver/lang/" + language + ".json";
        try (var stream = ServerLanguage.class.getResourceAsStream(path)) {
            if (stream == null) throw new IllegalStateException("Missing language file " + path);
            var json = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            var entries = new HashMap<String, String>();
            json.entrySet().forEach(entry -> entries.put(entry.getKey(), entry.getValue().getAsString()));
            messages = Map.copyOf(entries);
        } catch (IOException e) {
            throw new IllegalStateException("Could not load " + path, e);
        }
    }

    public static ServerLanguage of(ServerPlayer player) {
        String language = player.clientInformation().language();
        if ("es_ar".equals(language)) return ARGENTINE_SPANISH;
        return language.startsWith("es") ? SPANISH : ENGLISH;
    }

    public String text(String key, Object... args) {
        String template = messages.get(key);
        if (template == null) template = ENGLISH.messages.get(key);
        if (template == null) throw new IllegalArgumentException("Unknown language key " + key);
        return args.length == 0 ? template : String.format(locale, template, args);
    }

    public String coins(long cents) {
        return String.format(locale, "%.2f", java.math.BigDecimal.valueOf(cents, 2));
    }
}
