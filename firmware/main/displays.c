#include "displays.h"

#include <stdio.h>
#include <string.h>

#include "deck_config.h"
#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"

static const char *TAG = "displays";
static const char *PATH = DECK_FS_BASE "/displays.json";

static deck_display_t s_disp[DECK_MAX_DISPLAYS];
static int s_count;
static char s_active[24];
static SemaphoreHandle_t s_lock;

/* A single 1080p screen is the least surprising starting point: the app's
 * display editor opens on something sane instead of an empty list. */
static void load_fallback(void)
{
    s_count = 1;
    memset(s_disp, 0, sizeof(s_disp));
    snprintf(s_disp[0].id, sizeof(s_disp[0].id), "main");
    snprintf(s_disp[0].name, sizeof(s_disp[0].name), "Primary");
    s_disp[0].w = 1920;
    s_disp[0].h = 1080;
    s_disp[0].scale = 1.0f;
    s_disp[0].primary = true;
    snprintf(s_active, sizeof(s_active), "main");
}

static bool parse_into(const cJSON *obj, deck_display_t *out, int *out_count,
                       char *active, size_t activelen, char *err, size_t errlen)
{
    const cJSON *arr = cJSON_GetObjectItemCaseSensitive(obj, "displays");
    if (!cJSON_IsArray(arr)) {
        snprintf(err, errlen, "displays must be an array");
        return false;
    }
    int n = cJSON_GetArraySize(arr);
    if (n < 1 || n > DECK_MAX_DISPLAYS) {
        snprintf(err, errlen, "display count %d out of range 1..%d", n, DECK_MAX_DISPLAYS);
        return false;
    }

    memset(out, 0, sizeof(deck_display_t) * DECK_MAX_DISPLAYS);
    int k = 0;
    for (int i = 0; i < n; i++) {
        const cJSON *d = cJSON_GetArrayItem(arr, i);
        const cJSON *id = cJSON_GetObjectItemCaseSensitive(d, "id");
        const cJSON *w = cJSON_GetObjectItemCaseSensitive(d, "w");
        const cJSON *h = cJSON_GetObjectItemCaseSensitive(d, "h");
        if (!cJSON_IsString(id) || !cJSON_IsNumber(w) || !cJSON_IsNumber(h)) {
            snprintf(err, errlen, "display %d needs id, w, h", i);
            return false;
        }
        if (w->valueint < 64 || h->valueint < 64 ||
            w->valueint > DECK_ABS_MAX || h->valueint > DECK_ABS_MAX) {
            snprintf(err, errlen, "display %s size out of range", id->valuestring);
            return false;
        }
        snprintf(out[k].id, sizeof(out[k].id), "%s", id->valuestring);
        const cJSON *nm = cJSON_GetObjectItemCaseSensitive(d, "name");
        snprintf(out[k].name, sizeof(out[k].name), "%s",
                 cJSON_IsString(nm) ? nm->valuestring : id->valuestring);
        out[k].w = w->valueint;
        out[k].h = h->valueint;
        const cJSON *x = cJSON_GetObjectItemCaseSensitive(d, "x");
        const cJSON *y = cJSON_GetObjectItemCaseSensitive(d, "y");
        out[k].x = cJSON_IsNumber(x) ? x->valueint : 0;
        out[k].y = cJSON_IsNumber(y) ? y->valueint : 0;
        const cJSON *sc = cJSON_GetObjectItemCaseSensitive(d, "scale");
        out[k].scale = cJSON_IsNumber(sc) && sc->valuedouble > 0 ? (float)sc->valuedouble : 1.0f;
        out[k].primary = cJSON_IsTrue(cJSON_GetObjectItemCaseSensitive(d, "primary"));
        k++;
    }
    *out_count = k;

    const cJSON *act = cJSON_GetObjectItemCaseSensitive(obj, "active");
    if (cJSON_IsString(act)) {
        snprintf(active, activelen, "%s", act->valuestring);
    } else {
        snprintf(active, activelen, "%s", out[0].id);
    }
    return true;
}

static void persist_locked(void)
{
    cJSON *obj = cJSON_CreateObject();
    cJSON_AddStringToObject(obj, "active", s_active);
    cJSON *arr = cJSON_AddArrayToObject(obj, "displays");
    for (int i = 0; i < s_count; i++) {
        cJSON *d = cJSON_CreateObject();
        cJSON_AddStringToObject(d, "id", s_disp[i].id);
        cJSON_AddStringToObject(d, "name", s_disp[i].name);
        cJSON_AddNumberToObject(d, "w", s_disp[i].w);
        cJSON_AddNumberToObject(d, "h", s_disp[i].h);
        cJSON_AddNumberToObject(d, "x", s_disp[i].x);
        cJSON_AddNumberToObject(d, "y", s_disp[i].y);
        cJSON_AddNumberToObject(d, "scale", s_disp[i].scale);
        cJSON_AddBoolToObject(d, "primary", s_disp[i].primary);
        cJSON_AddItemToArray(arr, d);
    }
    char *txt = cJSON_PrintUnformatted(obj);
    cJSON_Delete(obj);
    if (!txt) {
        return;
    }
    FILE *f = fopen(PATH, "w");
    if (f) {
        fwrite(txt, 1, strlen(txt), f);
        fclose(f);
    } else {
        ESP_LOGW(TAG, "cannot write %s", PATH);
    }
    free(txt);
}

void displays_init(void)
{
    s_lock = xSemaphoreCreateMutex();
    load_fallback();

    FILE *f = fopen(PATH, "r");
    if (!f) {
        ESP_LOGI(TAG, "no stored layout, using a single 1920x1080 display");
        return;
    }
    fseek(f, 0, SEEK_END);
    long len = ftell(f);
    fseek(f, 0, SEEK_SET);
    if (len <= 0 || len > 8192) {
        fclose(f);
        return;
    }
    char *buf = malloc((size_t)len + 1);
    if (!buf) {
        fclose(f);
        return;
    }
    size_t got = fread(buf, 1, (size_t)len, f);
    fclose(f);
    buf[got] = 0;

    cJSON *obj = cJSON_Parse(buf);
    free(buf);
    if (!obj) {
        ESP_LOGW(TAG, "stored layout is not valid JSON, keeping fallback");
        return;
    }
    char err[96];
    deck_display_t tmp[DECK_MAX_DISPLAYS];
    int n = 0;
    char act[24];
    if (parse_into(obj, tmp, &n, act, sizeof(act), err, sizeof(err))) {
        memcpy(s_disp, tmp, sizeof(tmp));
        s_count = n;
        snprintf(s_active, sizeof(s_active), "%s", act);
        ESP_LOGI(TAG, "loaded %d display(s), active %s", s_count, s_active);
    } else {
        ESP_LOGW(TAG, "stored layout rejected: %s", err);
    }
    cJSON_Delete(obj);
}

bool displays_set_json(const cJSON *obj, char *err, size_t errlen)
{
    deck_display_t tmp[DECK_MAX_DISPLAYS];
    int n = 0;
    char act[24];
    if (!parse_into(obj, tmp, &n, act, sizeof(act), err, errlen)) {
        return false;
    }
    xSemaphoreTake(s_lock, portMAX_DELAY);
    memcpy(s_disp, tmp, sizeof(tmp));
    s_count = n;
    snprintf(s_active, sizeof(s_active), "%s", act);
    persist_locked();
    xSemaphoreGive(s_lock);
    ESP_LOGI(TAG, "layout replaced: %d display(s)", n);
    return true;
}

cJSON *displays_get_json(void)
{
    xSemaphoreTake(s_lock, portMAX_DELAY);
    cJSON *obj = cJSON_CreateObject();
    cJSON_AddStringToObject(obj, "active", s_active);
    cJSON *arr = cJSON_AddArrayToObject(obj, "displays");
    for (int i = 0; i < s_count; i++) {
        cJSON *d = cJSON_CreateObject();
        cJSON_AddStringToObject(d, "id", s_disp[i].id);
        cJSON_AddStringToObject(d, "name", s_disp[i].name);
        cJSON_AddNumberToObject(d, "w", s_disp[i].w);
        cJSON_AddNumberToObject(d, "h", s_disp[i].h);
        cJSON_AddNumberToObject(d, "x", s_disp[i].x);
        cJSON_AddNumberToObject(d, "y", s_disp[i].y);
        cJSON_AddNumberToObject(d, "scale", s_disp[i].scale);
        cJSON_AddBoolToObject(d, "primary", s_disp[i].primary);
        cJSON_AddItemToArray(arr, d);
    }
    int vw, vh;
    /* Reported for convenience so the editor can draw the virtual desktop
     * without recomputing the bounding box itself. */
    xSemaphoreGive(s_lock);
    displays_virtual_size(&vw, &vh);
    cJSON *v = cJSON_AddObjectToObject(obj, "virtual");
    cJSON_AddNumberToObject(v, "w", vw);
    cJSON_AddNumberToObject(v, "h", vh);
    return obj;
}

void displays_virtual_size(int *w, int *h)
{
    xSemaphoreTake(s_lock, portMAX_DELAY);
    int minx = 0, miny = 0, maxx = 0, maxy = 0;
    for (int i = 0; i < s_count; i++) {
        if (i == 0 || s_disp[i].x < minx) minx = s_disp[i].x;
        if (i == 0 || s_disp[i].y < miny) miny = s_disp[i].y;
        int rx = s_disp[i].x + s_disp[i].w;
        int ry = s_disp[i].y + s_disp[i].h;
        if (i == 0 || rx > maxx) maxx = rx;
        if (i == 0 || ry > maxy) maxy = ry;
    }
    xSemaphoreGive(s_lock);
    int vw = maxx - minx;
    int vh = maxy - miny;
    if (vw < 1) vw = 1;
    if (vh < 1) vh = 1;
    if (w) *w = vw;
    if (h) *h = vh;
}

static const deck_display_t *find_locked(const char *id)
{
    if (id && *id) {
        for (int i = 0; i < s_count; i++) {
            if (!strcmp(s_disp[i].id, id)) {
                return &s_disp[i];
            }
        }
    }
    /* Fall back to the active display, then the primary, then the first. A macro
     * that names a display which no longer exists still does something sensible
     * rather than refusing to run. */
    for (int i = 0; i < s_count; i++) {
        if (!strcmp(s_disp[i].id, s_active)) {
            return &s_disp[i];
        }
    }
    for (int i = 0; i < s_count; i++) {
        if (s_disp[i].primary) {
            return &s_disp[i];
        }
    }
    return s_count ? &s_disp[0] : NULL;
}

void displays_to_abs(const char *id, int px, int py, uint16_t *ax, uint16_t *ay)
{
    int minx = 0, miny = 0;
    int vw, vh;
    displays_virtual_size(&vw, &vh);

    xSemaphoreTake(s_lock, portMAX_DELAY);
    for (int i = 0; i < s_count; i++) {
        if (i == 0 || s_disp[i].x < minx) minx = s_disp[i].x;
        if (i == 0 || s_disp[i].y < miny) miny = s_disp[i].y;
    }
    const deck_display_t *d = find_locked(id);
    int vx = px, vy = py;
    if (d) {
        /* Clamp into the display first: a macro recorded on a 4K screen and
         * replayed against a 1080p one lands at the edge instead of off-screen. */
        if (vx < 0) vx = 0;
        if (vy < 0) vy = 0;
        if (vx > d->w - 1) vx = d->w - 1;
        if (vy > d->h - 1) vy = d->h - 1;
        vx += d->x - minx;
        vy += d->y - miny;
    }
    xSemaphoreGive(s_lock);

    long nx = (long)vx * DECK_ABS_MAX / (vw > 1 ? (vw - 1) : 1);
    long ny = (long)vy * DECK_ABS_MAX / (vh > 1 ? (vh - 1) : 1);
    if (nx < 0) nx = 0;
    if (ny < 0) ny = 0;
    if (nx > DECK_ABS_MAX) nx = DECK_ABS_MAX;
    if (ny > DECK_ABS_MAX) ny = DECK_ABS_MAX;
    if (ax) *ax = (uint16_t)nx;
    if (ay) *ay = (uint16_t)ny;
}

void displays_from_abs(uint16_t ax, uint16_t ay, const char **id, int *px, int *py)
{
    int vw, vh;
    displays_virtual_size(&vw, &vh);
    int vx = (int)((long)ax * (vw > 1 ? (vw - 1) : 1) / DECK_ABS_MAX);
    int vy = (int)((long)ay * (vh > 1 ? (vh - 1) : 1) / DECK_ABS_MAX);

    xSemaphoreTake(s_lock, portMAX_DELAY);
    int minx = 0, miny = 0;
    for (int i = 0; i < s_count; i++) {
        if (i == 0 || s_disp[i].x < minx) minx = s_disp[i].x;
        if (i == 0 || s_disp[i].y < miny) miny = s_disp[i].y;
    }
    const deck_display_t *hit = NULL;
    for (int i = 0; i < s_count; i++) {
        int lx = vx - (s_disp[i].x - minx);
        int ly = vy - (s_disp[i].y - miny);
        if (lx >= 0 && ly >= 0 && lx < s_disp[i].w && ly < s_disp[i].h) {
            hit = &s_disp[i];
            if (px) *px = lx;
            if (py) *py = ly;
            break;
        }
    }
    if (!hit) {
        hit = find_locked(NULL);
        if (px) *px = vx;
        if (py) *py = vy;
    }
    if (id) *id = hit ? hit->id : "";
    xSemaphoreGive(s_lock);
}
