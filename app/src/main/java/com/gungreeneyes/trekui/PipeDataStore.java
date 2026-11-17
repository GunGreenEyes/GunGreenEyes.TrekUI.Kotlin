package com.gungreeneyes.trekui;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Offline pipe collection shared by the pipe interfaces (scenes and pages). No account or network.
 *
 * <p>Three tables ({@code pipes}, {@code blends}, {@code journal}) are each stored as one JSON array
 * string in the {@code pipe_collection_v3} SharedPreferences file (the format of the original
 * PIPE_COLLECTION v3 prototype, kept so data survives upgrades), plus the current pipe/blend selection
 * and the ritual checklist bit mask. Every record has a stable string {@code id}.
 *
 * <p>The JSON manipulation (remove by id, import merge and validation, export document) lives in pure
 * static functions so it can be unit-tested; the instance methods only read and write preferences.
 */
public final class PipeDataStore {
    static final String PREFS = "pipe_collection_v3";
    static final String[] TABLES = {"pipes", "blends", "journal"};
    /** Export/import document version. */
    static final int SCHEMA = 1;
    /** Largest import document accepted, in characters. */
    static final int MAX_IMPORT_CHARS = 4_000_000;
    /** Most records accepted per table in one import. */
    static final int MAX_RECORDS_PER_TABLE = 2000;
    /** Longest record id accepted on import. */
    static final int MAX_ID_LENGTH = 100;
    /** Largest single record accepted on import, in characters of JSON. */
    static final int MAX_RECORD_CHARS = 20_000;

    static final String KEY_CURRENT_PIPE = "currentPipe", KEY_CURRENT_BLEND = "currentBlend", KEY_RITUAL_MASK = "ritualMask";

    private final SharedPreferences prefs;

    public PipeDataStore(Context c) {
        prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---- records -------------------------------------------------------------------------------

    /** All records of {@code table} (a fresh copy; a corrupt table reads as empty). */
    public synchronized JSONArray records(String table) {
        checkTable(table);
        try {
            return new JSONArray(prefs.getString(table, "[]"));
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    /** The record with {@code id} in {@code table}, or null. */
    public synchronized JSONObject get(String table, String id) {
        return find(records(table), id);
    }

    /**
     * Inserts or replaces a record (by its {@code id}; a missing id gets a new UUID) and stamps
     * {@code updatedAt}. The record moves to the end of the table.
     *
     * @return the record's id
     */
    public synchronized String upsert(String table, JSONObject incoming) {
        checkTable(table);
        String id = incoming.optString("id");
        if (id.isEmpty()) id = UUID.randomUUID().toString();
        JSONArray out = withoutId(records(table), id);
        try {
            incoming.put("id", id);
            incoming.put("updatedAt", System.currentTimeMillis());
        } catch (JSONException ex) {
            throw new IllegalArgumentException(ex);
        }
        out.put(incoming);
        prefs.edit().putString(table, out.toString()).apply();
        return id;
    }

    /** Removes the record with {@code id} from {@code table} (no-op if absent). */
    public synchronized void delete(String table, String id) {
        prefs.edit().putString(table, withoutId(records(table), id).toString()).apply();
    }

    /** Number of records in {@code table}. */
    public int count(String table) { return records(table).length(); }

    // ---- selection and ritual --------------------------------------------------------------------

    /** Id of the current pipe ({@code type} "pipes") or blend (anything else), or "". */
    public String selected(String type) { return prefs.getString(selectionKey(type), ""); }

    /** Makes {@code id} the current pipe or blend. */
    public void select(String type, String id) { prefs.edit().putString(selectionKey(type), id).apply(); }

    /** Today's ritual checklist: bit n set = step n done. */
    public int ritualMask() { return prefs.getInt(KEY_RITUAL_MASK, 0); }

    public void ritualMask(int mask) { prefs.edit().putInt(KEY_RITUAL_MASK, mask).apply(); }

    // ---- export / import -------------------------------------------------------------------------

    /** The whole collection as a versioned JSON document (see {@link #importJson}). */
    public synchronized JSONObject exportAll() {
        JSONArray[] tables = new JSONArray[TABLES.length];
        for (int i = 0; i < TABLES.length; i++) tables[i] = records(TABLES[i]);
        return exportDocument(tables, prefs.getString(KEY_CURRENT_PIPE, ""), prefs.getString(KEY_CURRENT_BLEND, ""),
                prefs.getInt(KEY_RITUAL_MASK, 0), System.currentTimeMillis());
    }

    /**
     * Imports an exported document. Everything is validated before storage is modified; records are
     * merged by their stable ids (imported ones replace local ones with the same id, local records
     * missing from the import are kept). The selection is replaced when the document has one.
     *
     * @return the number of records imported
     * @throws JSONException if the document is too large, malformed, of another schema, or has a bad record
     */
    public synchronized int importJson(String source) throws JSONException {
        JSONObject input = parseImport(source);
        int count = 0;
        SharedPreferences.Editor editor = prefs.edit();
        for (String table : TABLES) {
            JSONArray incoming = input.getJSONArray(table);
            editor.putString(table, mergeById(records(table), incoming).toString());
            count += incoming.length();
        }
        JSONObject sel = input.optJSONObject("selection");
        if (sel != null) {
            editor.putString(KEY_CURRENT_PIPE, sel.optString(KEY_CURRENT_PIPE, ""));
            editor.putString(KEY_CURRENT_BLEND, sel.optString(KEY_CURRENT_BLEND, ""));
            editor.putInt(KEY_RITUAL_MASK, sel.optInt(KEY_RITUAL_MASK, 0));
        }
        editor.apply();
        return count;
    }

    // ---- pure JSON helpers (unit-tested) -----------------------------------------------------------

    /** Pref key holding the current selection for {@code type}. */
    static String selectionKey(String type) {
        return "pipes".equals(type) ? KEY_CURRENT_PIPE : KEY_CURRENT_BLEND;
    }

    /** Throws for any table name outside {@link #TABLES}. */
    static void checkTable(String t) {
        for (String ok : TABLES) if (ok.equals(t)) return;
        throw new IllegalArgumentException("Unknown collection " + t);
    }

    /** The first object in {@code a} whose {@code id} equals {@code id}, or null. */
    static JSONObject find(JSONArray a, String id) {
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && id.equals(o.optString("id"))) return o;
        }
        return null;
    }

    /** A copy of {@code a} without the objects whose id is {@code id} (non-objects are dropped too). */
    static JSONArray withoutId(JSONArray a, String id) {
        JSONArray out = new JSONArray();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && !id.equals(o.optString("id"))) out.put(o);
        }
        return out;
    }

    /**
     * Parses and validates an import document: size limit, schema version, every table present with
     * at most {@link #MAX_RECORDS_PER_TABLE} records, each with a non-empty id of at most
     * {@link #MAX_ID_LENGTH} characters and at most {@link #MAX_RECORD_CHARS} characters of JSON.
     */
    static JSONObject parseImport(String source) throws JSONException {
        if (source.length() > MAX_IMPORT_CHARS) throw new JSONException("Import exceeds 4MB limit");
        JSONObject input = new JSONObject(source);
        if (input.optInt("schema") != SCHEMA) throw new JSONException("Unsupported collection schema");
        for (String table : TABLES) {
            JSONArray incoming = input.getJSONArray(table);
            if (incoming.length() > MAX_RECORDS_PER_TABLE) throw new JSONException("Too many records in " + table);
            for (int i = 0; i < incoming.length(); i++) {
                JSONObject o = incoming.getJSONObject(i);
                String id = o.optString("id");
                if (id.isEmpty() || id.length() > MAX_ID_LENGTH) throw new JSONException("Missing/invalid ID in " + table);
                if (o.toString().length() > MAX_RECORD_CHARS) throw new JSONException("Record too large");
            }
        }
        return input;
    }

    /**
     * Merges {@code incoming} into {@code existing} by id: existing order first, replaced in place when
     * an imported record has the same id, then new imported records in import order.
     */
    static JSONArray mergeById(JSONArray existing, JSONArray incoming) throws JSONException {
        Map<String, JSONObject> byId = new LinkedHashMap<>();
        for (int i = 0; i < existing.length(); i++) {
            JSONObject o = existing.optJSONObject(i);
            if (o != null) byId.put(o.optString("id"), o);
        }
        for (int i = 0; i < incoming.length(); i++) {
            JSONObject o = incoming.getJSONObject(i);
            byId.put(o.optString("id"), o);
        }
        JSONArray combined = new JSONArray();
        for (JSONObject rec : byId.values()) combined.put(rec);
        return combined;
    }

    /** Builds the export document for the given tables (in {@link #TABLES} order) and selection. */
    static JSONObject exportDocument(JSONArray[] tables, String currentPipe, String currentBlend, int ritualMask, long exportedAt) {
        JSONObject result = new JSONObject();
        try {
            result.put("schema", SCHEMA);
            result.put("exportedAt", exportedAt);
            for (int i = 0; i < TABLES.length; i++) result.put(TABLES[i], tables[i]);
            JSONObject meta = new JSONObject();
            meta.put(KEY_CURRENT_PIPE, currentPipe);
            meta.put(KEY_CURRENT_BLEND, currentBlend);
            meta.put(KEY_RITUAL_MASK, ritualMask);
            result.put("selection", meta);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        return result;
    }
}
