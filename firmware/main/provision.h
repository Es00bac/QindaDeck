/* A small HTTP server, so a phone can join the deck to Wi-Fi before the app has
 * any way to reach it, and so `curl` is a usable debugging tool afterwards. */
#pragma once

void provision_init(void);
