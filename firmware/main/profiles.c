#include "profiles.h"

#include <dirent.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "action.h"
#include "deck_config.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"
#include "hid_keymap.h"
#include "macro.h"
#include "nvs.h"
#include "nvs_flash.h"
#include "usb_hid.h"

static const char *TAG = "profiles";

/* Provided by defaults.c. */
extern const char *const deck_default_profiles[];
extern const int deck_default_profile_count;

static SemaphoreHandle_t s_lock;
static char s_active[40];
static int s_active_page;
/* Counting profiles means walking the SPIFFS directory, which is slow enough to
 * dominate a status round trip. The count only changes when we change it. */
static int s_count = -1;

static void path_for(const char *slug, char *out, size_t len)
{
    snprintf(out, len, DECK_FS_BASE "/p_%s.json", slug);
}

static bool valid_slug(const char *s)
{
    if (!s || !*s || strlen(s) > 31) {
        return false;
    }
    for (const char *p = s; *p; p++) {
        bool ok = (*p >= 'a' && *p <= 'z') || (*p >= '0' && *p <= '9') ||
                  *p == '-' || *p == '_';
        if (!ok) {
            return false;
        }
    }
    return true;
}

static char *read_file(const char *path)
{
    FILE *f = fopen(path, "r");
    if (!f) {
        return NULL;
    }
    fseek(f, 0, SEEK_END);
    long len = ftell(f);
    fseek(f, 0, SEEK_SET);
    if (len <= 0 || len > 64 * 1024) {
        fclose(f);
        return NULL;
    }
    char *buf = malloc((size_t)len + 1);
    if (!buf) {
        fclose(f);
        return NULL;
    }
    size_t got = fread(buf, 1, (size_t)len, f);
    fclose(f);
    buf[got] = 0;
    return buf;
}

static bool write_file(const char *path, const char *text)
{
    FILE *f = fopen(path, "w");
    if (!f) {
        return false;
    }
    size_t len = strlen(text);
    bool ok = fwrite(text, 1, len, f) == len;
    fclose(f);
    return ok;
}

/* ------------------------------------------------------------------ validation */

static bool validate_button(const cJSON *b, char *err, size_t errlen)
{
    const cJSON *idx = cJSON_GetObjectItemCaseSensitive(b, "index");
    if (!cJSON_IsNumber(idx) || idx->valueint < 0 || idx->valueint > 63) {
        snprintf(err, errlen, "button index out of range");
        return false;
    }
    /* A button *is* an action object with presentation fields attached, so the
     * shared validator is the whole check. */
    char aerr[180] = {0};
    if (!action_validate(b, aerr, sizeof(aerr))) {
        snprintf(err, errlen, "button %d: %s", idx->valueint, aerr);
        return false;
    }
    return true;
}

static bool validate_profile(const cJSON *p, char *err, size_t errlen)
{
    const cJSON *slug = cJSON_GetObjectItemCaseSensitive(p, "slug");
    if (!cJSON_IsString(slug) || !valid_slug(slug->valuestring)) {
        snprintf(err, errlen, "profile needs a slug of [a-z0-9_-]");
        return false;
    }
    if (!cJSON_IsString(cJSON_GetObjectItemCaseSensitive(p, "name"))) {
        snprintf(err, errlen, "profile needs a name");
        return false;
    }
    const cJSON *pages = cJSON_GetObjectItemCaseSensitive(p, "pages");
    if (!cJSON_IsArray(pages) || cJSON_GetArraySize(pages) < 1) {
        snprintf(err, errlen, "profile needs at least one page");
        return false;
    }
    int np = cJSON_GetArraySize(pages);
    if (np > 16) {
        snprintf(err, errlen, "profile has more than 16 pages");
        return false;
    }
    for (int i = 0; i < np; i++) {
        const cJSON *pg = cJSON_GetArrayItem(pages, i);
        const cJSON *cols = cJSON_GetObjectItemCaseSensitive(pg, "cols");
        const cJSON *rows = cJSON_GetObjectItemCaseSensitive(pg, "rows");
        if (!cJSON_IsNumber(cols) || !cJSON_IsNumber(rows) ||
            cols->valueint < 1 || cols->valueint > 8 ||
            rows->valueint < 1 || rows->valueint > 8) {
            snprintf(err, errlen, "page %d: cols/rows must be 1..8", i);
            return false;
        }
        const cJSON *btns = cJSON_GetObjectItemCaseSensitive(pg, "buttons");
        if (!cJSON_IsArray(btns)) {
            snprintf(err, errlen, "page %d: buttons must be an array", i);
            return false;
        }
        int nb = cJSON_GetArraySize(btns);
        for (int j = 0; j < nb; j++) {
            if (!validate_button(cJSON_GetArrayItem(btns, j), err, errlen)) {
                char tmp[200];
                snprintf(tmp, sizeof(tmp), "page %d: %s", i, err);
                snprintf(err, errlen, "%s", tmp);
                return false;
            }
        }
    }
    return true;
}

/* ------------------------------------------------------------------ store */

static void save_active(void)
{
    nvs_handle_t h;
    if (nvs_open("deck", NVS_READWRITE, &h) != ESP_OK) {
        return;
    }
    nvs_set_str(h, "active", s_active);
    nvs_set_i32(h, "page", s_active_page);
    nvs_commit(h);
    nvs_close(h);
}

static void load_active(void)
{
    nvs_handle_t h;
    if (nvs_open("deck", NVS_READONLY, &h) != ESP_OK) {
        return;
    }
    size_t len = sizeof(s_active);
    if (nvs_get_str(h, "active", s_active, &len) != ESP_OK) {
        s_active[0] = 0;
    }
    int32_t pg = 0;
    if (nvs_get_i32(h, "page", &pg) == ESP_OK) {
        s_active_page = (int)pg;
    }
    nvs_close(h);
}

static void install_defaults(bool overwrite)
{
    for (int i = 0; i < deck_default_profile_count; i++) {
        cJSON *p = cJSON_Parse(deck_default_profiles[i]);
        if (!p) {
            ESP_LOGE(TAG, "built-in profile %d is not valid JSON", i);
            continue;
        }
        const cJSON *slug = cJSON_GetObjectItemCaseSensitive(p, "slug");
        if (!cJSON_IsString(slug)) {
            cJSON_Delete(p);
            continue;
        }
        char path[64];
        path_for(slug->valuestring, path, sizeof(path));
        FILE *probe = fopen(path, "r");
        if (probe) {
            fclose(probe);
            if (!overwrite) {
                cJSON_Delete(p);
                continue;
            }
        }
        char err[200] = {0};
        if (!validate_profile(p, err, sizeof(err))) {
            ESP_LOGE(TAG, "built-in profile '%s' rejected: %s", slug->valuestring, err);
            cJSON_Delete(p);
            continue;
        }
        if (!write_file(path, deck_default_profiles[i])) {
            ESP_LOGE(TAG, "cannot write %s", path);
        } else {
            ESP_LOGI(TAG, "installed built-in profile '%s'", slug->valuestring);
        }
        cJSON_Delete(p);
    }
}

void profiles_init(void)
{
    s_lock = xSemaphoreCreateMutex();

    install_defaults(false);
    s_count = -1;
    load_active();

    if (!s_active[0]) {
        cJSON *list = profiles_list();
        const cJSON *first = list ? cJSON_GetArrayItem(list, 0) : NULL;
        const cJSON *slug = first ? cJSON_GetObjectItemCaseSensitive(first, "slug") : NULL;
        if (cJSON_IsString(slug)) {
            snprintf(s_active, sizeof(s_active), "%s", slug->valuestring);
            s_active_page = 0;
            save_active();
        }
        cJSON_Delete(list);
    }
    ESP_LOGI(TAG, "%d profile(s), active '%s' page %d",
             profiles_count(), s_active, s_active_page);
}

cJSON *profiles_list(void)
{
    cJSON *arr = cJSON_CreateArray();
    DIR *d = opendir(DECK_FS_BASE);
    if (!d) {
        return arr;
    }
    struct dirent *e;
    while ((e = readdir(d)) != NULL) {
        if (strncmp(e->d_name, "p_", 2) != 0) {
            continue;
        }
        char path[64];
        snprintf(path, sizeof(path), DECK_FS_BASE "/%.40s", e->d_name);
        char *txt = read_file(path);
        if (!txt) {
            continue;
        }
        cJSON *p = cJSON_Parse(txt);
        free(txt);
        if (!p) {
            continue;
        }
        const cJSON *slug = cJSON_GetObjectItemCaseSensitive(p, "slug");
        const cJSON *name = cJSON_GetObjectItemCaseSensitive(p, "name");
        if (cJSON_IsString(slug) && cJSON_IsString(name)) {
            cJSON *sum = cJSON_CreateObject();
            cJSON_AddStringToObject(sum, "slug", slug->valuestring);
            cJSON_AddStringToObject(sum, "name", name->valuestring);
            const cJSON *icon = cJSON_GetObjectItemCaseSensitive(p, "icon");
            if (cJSON_IsString(icon)) {
                cJSON_AddStringToObject(sum, "icon", icon->valuestring);
            }
            const cJSON *accent = cJSON_GetObjectItemCaseSensitive(p, "accent");
            if (cJSON_IsString(accent)) {
                cJSON_AddStringToObject(sum, "accent", accent->valuestring);
            }
            const cJSON *pages = cJSON_GetObjectItemCaseSensitive(p, "pages");
            cJSON_AddNumberToObject(sum, "pages",
                                    cJSON_IsArray(pages) ? cJSON_GetArraySize(pages) : 0);
            cJSON_AddBoolToObject(sum, "builtin",
                cJSON_IsTrue(cJSON_GetObjectItemCaseSensitive(p, "builtin")));
            cJSON_AddItemToArray(arr, sum);
        }
        cJSON_Delete(p);
    }
    closedir(d);
    return arr;
}

static int count_on_disk(void)
{
    int n = 0;
    DIR *d = opendir(DECK_FS_BASE);
    if (!d) {
        return 0;
    }
    struct dirent *e;
    while ((e = readdir(d)) != NULL) {
        if (strncmp(e->d_name, "p_", 2) == 0) {
            n++;
        }
    }
    closedir(d);
    return n;
}

int profiles_count(void)
{
    if (s_count < 0) {
        s_count = count_on_disk();
    }
    return s_count;
}

cJSON *profiles_get(const char *slug)
{
    if (!valid_slug(slug)) {
        return NULL;
    }
    char path[64];
    path_for(slug, path, sizeof(path));
    char *txt = read_file(path);
    if (!txt) {
        return NULL;
    }
    cJSON *p = cJSON_Parse(txt);
    free(txt);
    return p;
}

bool profiles_put(const cJSON *profile, char *err, size_t errlen)
{
    if (!validate_profile(profile, err, errlen)) {
        return false;
    }
    const cJSON *slug = cJSON_GetObjectItemCaseSensitive(profile, "slug");
    if (profiles_count() >= DECK_MAX_PROFILES && !profiles_get(slug->valuestring)) {
        snprintf(err, errlen, "profile limit of %d reached", DECK_MAX_PROFILES);
        return false;
    }
    char *txt = cJSON_PrintUnformatted(profile);
    if (!txt) {
        snprintf(err, errlen, "out of memory serialising profile");
        return false;
    }
    char path[64];
    path_for(slug->valuestring, path, sizeof(path));
    bool ok = write_file(path, txt);
    free(txt);
    if (!ok) {
        snprintf(err, errlen, "cannot write profile storage");
        return false;
    }
    s_count = -1;
    ESP_LOGI(TAG, "stored profile '%s'", slug->valuestring);
    return true;
}

bool profiles_del(const char *slug)
{
    if (!valid_slug(slug)) {
        return false;
    }
    char path[64];
    path_for(slug, path, sizeof(path));
    if (remove(path) != 0) {
        return false;
    }
    s_count = -1;
    if (!strcmp(s_active, slug)) {
        s_active[0] = 0;
        s_active_page = 0;
        save_active();
    }
    return true;
}

bool profiles_set_active(const char *slug, int page)
{
    cJSON *p = profiles_get(slug);
    if (!p) {
        return false;
    }
    const cJSON *pages = cJSON_GetObjectItemCaseSensitive(p, "pages");
    int np = cJSON_IsArray(pages) ? cJSON_GetArraySize(pages) : 0;
    cJSON_Delete(p);
    if (page < 0 || (np && page >= np)) {
        page = 0;
    }
    xSemaphoreTake(s_lock, portMAX_DELAY);
    snprintf(s_active, sizeof(s_active), "%s", slug);
    s_active_page = page;
    save_active();
    xSemaphoreGive(s_lock);
    return true;
}

void profiles_get_active(char *slug, size_t len, int *page)
{
    xSemaphoreTake(s_lock, portMAX_DELAY);
    snprintf(slug, len, "%s", s_active);
    if (page) {
        *page = s_active_page;
    }
    xSemaphoreGive(s_lock);
}

void profiles_factory_reset(void)
{
    DIR *d = opendir(DECK_FS_BASE);
    if (d) {
        struct dirent *e;
        while ((e = readdir(d)) != NULL) {
            if (strncmp(e->d_name, "p_", 2) == 0) {
                char path[64];
                snprintf(path, sizeof(path), DECK_FS_BASE "/%.40s", e->d_name);
                remove(path);
            }
        }
        closedir(d);
    }
    install_defaults(true);
    s_count = -1;
    s_active[0] = 0;
    s_active_page = 0;
    save_active();
}

/* ------------------------------------------------------------------ dispatch */

bool profiles_press(const char *slug, int page, int index, const char *state,
                    char *err, size_t errlen)
{
    char use_slug[40];
    int use_page;
    profiles_get_active(use_slug, sizeof(use_slug), &use_page);
    if (slug && *slug) {
        snprintf(use_slug, sizeof(use_slug), "%s", slug);
    }
    if (page >= 0) {
        use_page = page;
    }

    bool down = !state || !strcmp(state, "down") || !strcmp(state, "tap");
    bool up = state && (!strcmp(state, "up") || !strcmp(state, "tap"));

    /* A release always tears down any hold, even if the profile has since been
     * replaced: otherwise a key could stay down forever. */
    if (!down && up) {
        action_repeat_disarm();
    }

    cJSON *p = profiles_get(use_slug);
    if (!p) {
        snprintf(err, errlen, "no profile '%s'", use_slug);
        return false;
    }

    bool ok = false;
    const cJSON *pages = cJSON_GetObjectItemCaseSensitive(p, "pages");
    const cJSON *pg = cJSON_IsArray(pages) ? cJSON_GetArrayItem(pages, use_page) : NULL;
    if (!pg) {
        snprintf(err, errlen, "profile '%s' has no page %d", use_slug, use_page);
        goto out;
    }

    const cJSON *btns = cJSON_GetObjectItemCaseSensitive(pg, "buttons");
    const cJSON *b = NULL;
    int nb = cJSON_IsArray(btns) ? cJSON_GetArraySize(btns) : 0;
    for (int i = 0; i < nb; i++) {
        const cJSON *cand = cJSON_GetArrayItem(btns, i);
        const cJSON *idx = cJSON_GetObjectItemCaseSensitive(cand, "index");
        if (cJSON_IsNumber(idx) && idx->valueint == index) {
            b = cand;
            break;
        }
    }
    if (!b) {
        /* An empty cell is not an error: the grid is sparse by design. */
        ok = true;
        goto out;
    }

    const action_ctx_t ctx = {
        .profile_slug = use_slug,
        .macros = cJSON_GetObjectItemCaseSensitive(p, "macros"),
    };
    ok = action_fire(b, down, up, &ctx, err, errlen);

out:
    cJSON_Delete(p);
    return ok;
}
