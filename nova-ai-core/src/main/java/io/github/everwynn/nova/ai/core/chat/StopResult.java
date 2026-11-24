package io.github.everwynn.nova.ai.core.chat;

/**
 * 停止操作的结果。
 */
public final class StopResult {

    private static final StopResult NOT_RUNNING = new StopResult(false, 0);

    private final boolean stopped;
    private final int partialLength;

    private StopResult(boolean stopped, int partialLength) {
        this.stopped = stopped;
        this.partialLength = partialLength;
    }

    public static StopResult notRunning() {
        return NOT_RUNNING;
    }

    public static StopResult stopped(int partialLength) {
        return new StopResult(true, partialLength);
    }

    /** 是否真的停止了正在进行的生成 */
    public boolean isStopped() {
        return stopped;
    }

    /** 停止时已生成内容的字符数 */
    public int getPartialLength() {
        return partialLength;
    }
}
