#include "sd_ops.h"

#include <dirent.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>

#include "esp_log.h"
#include "mbedtls/base64.h"
#include "net_link.h"
#include "sdcard.h"

static const char *TAG = "sd_ops";

/* 24 KiB binary per frame: base64 that and it still fits the 64 KiB wire cap
 * with the JSON wrapper around it. */
#define SD_CHUNK        (24 * 1024)
#define SD_B64_BUF      (((SD_CHUNK + 2) / 3) * 4 + 1)
#define SD_LIST_MAX     256
#define SD_ROOT         "/sdcard"

/* ------------------------------------------------------------------ helpers */

static cJSON *reply_for(const cJSON *req, bool ok)
{
    cJSON *r = cJSON_CreateObject();
    const cJSON *id = cJSON_GetObjectItemCaseSensitive(req, "id");
    cJSON_AddNumberToObject(r, "id", cJSON_IsNumber(id) ? id->valueint : 0);
    cJSON_AddBoolToObject(r, "ok", ok);
    return r;
}

static void fail(const cJSON *req, const char *msg)
{
    cJSON *r = reply_for(req, false);
    cJSON_AddStringToObject(r, "err", msg);
    net_link_send_json(r);
}

/* Every request path shares the same preconditions: a card must exist and the
 * firmware must own it. Ops that fail either get the same explanation. */
static bool ready(const cJSON *req)
{
    if (!sdcard_present()) {
        fail(req, "no SD card");
        return false;
    }
    if (!sdcard_owner_app()) {
        fail(req, "the USB host owns the card; sd_take first");
        return false;
    }
    sdcard_note_activity();
    return true;
}

/* Map a client path ("/dir/file") onto the VFS mount, refusing escapes. */
static bool vfs_path(char *out, size_t outlen, const char *path)
{
    if (!path || path[0] != '/' || strstr(path, "..")) {
        return false;
    }
    int n = snprintf(out, outlen, SD_ROOT "%s", path);
    return n > 0 && (size_t)n < outlen;
}

static const cJSON *str_arg(const cJSON *req, const char *key)
{
    const cJSON *v = cJSON_GetObjectItemCaseSensitive(req, key);
    return cJSON_IsString(v) ? v : NULL;
}

static long num_arg(const cJSON *req, const char *key, long dflt)
{
    const cJSON *v = cJSON_GetObjectItemCaseSensitive(req, key);
    return cJSON_IsNumber(v) ? (long)v->valuedouble : dflt;
}

/* ------------------------------------------------------------------ ops */

static void op_status(cJSON *req)
{
    cJSON *r = reply_for(req, true);
    cJSON_AddBoolToObject(r, "present", sdcard_present());
    cJSON_AddNumberToObject(r, "capacity_mb", (double)(sdcard_capacity_bytes() >> 20));
    cJSON_AddStringToObject(r, "owner",
                            !sdcard_present() ? "none"
                            : sdcard_owner_app() ? "app" : "usb");
    net_link_send_json(r);
}

static void op_take(cJSON *req)
{
    esp_err_t err = sdcard_take();
    if (err != ESP_OK) {
        fail(req, "no SD card to take");
        return;
    }
    /* The switch succeeds even when the card has no mountable filesystem —
     * surface that now so the client can say so instead of listing nothing. */
    DIR *d = opendir(SD_ROOT);
    cJSON *r = reply_for(req, true);
    cJSON_AddBoolToObject(r, "filesystem", d != NULL);
    if (d) {
        closedir(d);
    }
    net_link_send_json(r);
}

static void op_release(cJSON *req)
{
    esp_err_t err = sdcard_release();
    if (err != ESP_OK) {
        fail(req, "no SD card to release");
        return;
    }
    net_link_send_json(reply_for(req, true));
}

static void op_list(cJSON *req)
{
    if (!ready(req)) {
        return;
    }
    const cJSON *path = str_arg(req, "path");
    char full[256];
    if (!path || !vfs_path(full, sizeof(full), path->valuestring)) {
        fail(req, "bad path");
        return;
    }
    DIR *d = opendir(full);
    if (!d) {
        fail(req, "cannot open directory");
        return;
    }
    cJSON *r = reply_for(req, true);
    cJSON_AddStringToObject(r, "path", path->valuestring);
    cJSON *entries = cJSON_AddArrayToObject(r, "entries");

    struct dirent *de;
    int count = 0;
    bool truncated = false;
    while ((de = readdir(d)) != NULL) {
        if (!strcmp(de->d_name, ".") || !strcmp(de->d_name, "..")) {
            continue;
        }
        if (count >= SD_LIST_MAX) {
            truncated = true;
            break;
        }
        char fp[512];
        struct stat st;
        snprintf(fp, sizeof(fp), "%s/%s", full, de->d_name);
        bool is_dir = de->d_type == DT_DIR;
        long size = 0, mtime = 0;
        if (stat(fp, &st) == 0) {
            is_dir = S_ISDIR(st.st_mode);
            size = (long)st.st_size;
            mtime = (long)st.st_mtime;
        }
        cJSON *e = cJSON_CreateObject();
        cJSON_AddStringToObject(e, "name", de->d_name);
        cJSON_AddBoolToObject(e, "dir", is_dir);
        cJSON_AddNumberToObject(e, "size", (double)size);
        cJSON_AddNumberToObject(e, "mtime", (double)mtime);
        cJSON_AddItemToArray(entries, e);
        count++;
    }
    closedir(d);
    cJSON_AddBoolToObject(r, "truncated", truncated);
    net_link_send_json(r);
}

static void op_read(cJSON *req)
{
    if (!ready(req)) {
        return;
    }
    const cJSON *path = str_arg(req, "path");
    long offset = num_arg(req, "offset", 0);
    long max = num_arg(req, "max", SD_CHUNK);
    if (max <= 0 || max > SD_CHUNK) {
        max = SD_CHUNK;
    }
    char full[256];
    if (!path || !vfs_path(full, sizeof(full), path->valuestring)) {
        fail(req, "bad path");
        return;
    }
    FILE *f = fopen(full, "rb");
    if (!f) {
        fail(req, "cannot open file");
        return;
    }
    fseek(f, 0, SEEK_END);
    long size = ftell(f);
    if (offset < 0 || offset > size) {
        offset = size;
    }
    fseek(f, offset, SEEK_SET);

    uint8_t *buf = malloc(SD_CHUNK);
    char *b64 = malloc(SD_B64_BUF);
    if (!buf || !b64) {
        fclose(f);
        free(buf);
        free(b64);
        fail(req, "out of memory");
        return;
    }
    size_t got = fread(buf, 1, (size_t)max, f);
    fclose(f);

    size_t b64len = 0;
    if (mbedtls_base64_encode((uint8_t *)b64, SD_B64_BUF, &b64len,
                              buf, got) != 0) {
        free(buf);
        free(b64);
        fail(req, "encode failed");
        return;
    }
    b64[b64len] = 0;

    cJSON *r = reply_for(req, true);
    cJSON_AddNumberToObject(r, "size", (double)size);
    cJSON_AddNumberToObject(r, "offset", (double)offset);
    cJSON_AddStringToObject(r, "data", b64);
    cJSON_AddBoolToObject(r, "eof", offset + (long)got >= size);
    net_link_send_json(r);
    free(buf);
    free(b64);
}

static void op_write(cJSON *req)
{
    if (!ready(req)) {
        return;
    }
    const cJSON *path = str_arg(req, "path");
    const cJSON *data = str_arg(req, "data");
    long offset = num_arg(req, "offset", 0);
    char full[256];
    if (!path || !data || !vfs_path(full, sizeof(full), path->valuestring)) {
        fail(req, "bad path or data");
        return;
    }
    size_t dlen = strlen(data->valuestring);
    if (dlen > SD_B64_BUF) {
        fail(req, "chunk too large");
        return;
    }
    uint8_t *buf = malloc(SD_CHUNK);
    if (!buf) {
        fail(req, "out of memory");
        return;
    }
    size_t olen = 0;
    if (mbedtls_base64_decode(buf, SD_CHUNK, &olen,
                              (const uint8_t *)data->valuestring, dlen) != 0) {
        free(buf);
        fail(req, "bad base64");
        return;
    }
    /* Offset 0 creates or truncates; anything else resumes in place. */
    FILE *f = fopen(full, offset == 0 ? "wb" : "r+b");
    if (!f) {
        free(buf);
        fail(req, "cannot open file");
        return;
    }
    if (offset > 0) {
        fseek(f, offset, SEEK_SET);
    }
    size_t wrote = fwrite(buf, 1, olen, f);
    fclose(f);
    free(buf);
    if (wrote != olen) {
        fail(req, "short write (card full?)");
        return;
    }
    cJSON *r = reply_for(req, true);
    cJSON_AddNumberToObject(r, "written", (double)wrote);
    net_link_send_json(r);
}

static void op_delete(cJSON *req)
{
    if (!ready(req)) {
        return;
    }
    const cJSON *path = str_arg(req, "path");
    char full[256];
    if (!path || !vfs_path(full, sizeof(full), path->valuestring)) {
        fail(req, "bad path");
        return;
    }
    if (remove(full) != 0) {
        fail(req, "delete failed (non-empty directory?)");
        return;
    }
    net_link_send_json(reply_for(req, true));
}

static void op_mkdir(cJSON *req)
{
    if (!ready(req)) {
        return;
    }
    const cJSON *path = str_arg(req, "path");
    char full[256];
    if (!path || !vfs_path(full, sizeof(full), path->valuestring)) {
        fail(req, "bad path");
        return;
    }
    if (mkdir(full, 0755) != 0) {
        fail(req, "mkdir failed");
        return;
    }
    net_link_send_json(reply_for(req, true));
}

bool sd_ops_handle(const char *op, cJSON *req)
{
    if (!strcmp(op, "sd_status"))       { op_status(req); }
    else if (!strcmp(op, "sd_take"))    { op_take(req); }
    else if (!strcmp(op, "sd_release")) { op_release(req); }
    else if (!strcmp(op, "sd_list"))    { op_list(req); }
    else if (!strcmp(op, "sd_read"))    { op_read(req); }
    else if (!strcmp(op, "sd_write"))   { op_write(req); }
    else if (!strcmp(op, "sd_delete"))  { op_delete(req); }
    else if (!strcmp(op, "sd_mkdir"))   { op_mkdir(req); }
    else                                { return false; }
    return true;
}
