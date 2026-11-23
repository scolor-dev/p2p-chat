package com.p2pchat.app.ui.chat

import com.p2pchat.app.data.message.MessageDirection

/**
 * 吹き出しに「しっぽ」を付けるかを、メッセージごとに返す。
 * 同じ方向のメッセージが続く間は、いちばん最後（下）の 1 件だけが true。
 * 方向が変わるところと、一覧の最後も true になる。
 */
fun tailFlags(directions: List<MessageDirection>): List<Boolean> =
    directions.mapIndexed { index, direction -> directions.getOrNull(index + 1) != direction }
