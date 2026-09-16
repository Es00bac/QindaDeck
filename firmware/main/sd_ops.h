/* Wi-Fi file access to the SD card, over the JSON control channel.
 *
 * The card spends its life owned by the USB host as a block device. To browse
 * or move files, a client takes the card (the host sees its drive eject),
 * does its I/O, and releases. An idle minute releases automatically. */
#pragma once

#include <stdbool.h>

#include "cJSON.h"

/* Handle an sd_* op. Returns true when the op was recognised (the reply has
 * been sent); false lets the caller continue dispatching. */
bool sd_ops_handle(const char *op, cJSON *req);
