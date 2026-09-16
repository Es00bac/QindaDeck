/* Control-message dispatch: JSON operations from the phone, plus the compact
 * binary path used for live trackpad and key input. */
#pragma once

#include <stddef.h>
#include <stdint.h>

void protocol_init(void);

/* Called when a client attaches, so the device can volunteer its state instead
 * of making the app ask. */
void protocol_on_connect(void);

void protocol_handle_frame(uint8_t type, const uint8_t *payload, size_t len);
