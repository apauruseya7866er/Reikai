package reikai.domain.track

/**
 * A tracker the user lets follow rereads: a chapter read at or below the service's progress is still
 * sent, where [pushChapterProgress] holds every other tracker forward only. NovelUpdates, while its
 * "Never move progress back" setting is off.
 */
interface RereadPushTracker {

    val followsRereads: Boolean
}
