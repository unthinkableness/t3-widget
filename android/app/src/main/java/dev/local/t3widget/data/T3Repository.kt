package dev.local.t3widget.data

import android.content.Context

/**
 * Source of widget data. Implementations throw on failure ([ApiException] for categorised errors); the caller
 * ([dev.local.t3widget.work.Refresher]) turns exceptions into an error state while keeping the last good numbers.
 */
interface T3Repository {
    suspend fun fetch(previous: WidgetState): WidgetState

    companion object {
        fun from(context: Context, settings: Settings): T3Repository =
            if (settings.useFakeData) FakeT3Repository()
            else RemoteT3Repository(context, settings)
    }
}
