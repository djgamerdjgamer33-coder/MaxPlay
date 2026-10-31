import React, { useState, useRef, useEffect } from 'react';

interface ProgressBarProps {
  currentTime: number; // in seconds
  duration: number;    // in seconds
  buffered?: number;   // 0 to 100 percentage
  onSeek: (time: number) => void;
  onScrubStart?: () => void;
  onScrubEnd?: () => void;
  videoElement?: HTMLVideoElement | null;
  posterUrl?: string;
  timeOffset?: number;
  skipMarkers?: {
    intro?: { start: number; end: number };
    outro?: { start: number; end: number };
    credits?: { start: number; end: number };
  };
}

export const ProgressBar: React.FC<ProgressBarProps> = ({
  currentTime,
  duration,
  buffered = 0,
  onSeek,
  onScrubStart,
  onScrubEnd,
  videoElement,
  posterUrl,
  timeOffset = 0,
  skipMarkers,
}) => {
  const [isHovered, setIsHovered] = useState(false);
  const [isDragging, setIsDragging] = useState(false);
  const [dragTime, setDragTime] = useState<number | null>(null);
  const [hoverTime, setHoverTime] = useState<number | null>(null);
  const [hasCanvasFrame, setHasCanvasFrame] = useState(false);
  const dragTimeRef = useRef<number | null>(null);
  const barRef = useRef<HTMLDivElement | null>(null);
  const previewCanvasRef = useRef<HTMLCanvasElement | null>(null);
  const lastPreviewSeekRef = useRef<number>(0);

  const displayTime = isDragging && dragTime !== null
    ? dragTime
    : isHovered && hoverTime !== null
    ? hoverTime
    : currentTime;
  const progressPercent = duration > 0 ? Math.min(100, Math.max(0, (displayTime / duration) * 100)) : 0;
  const clampedPreviewPercent = Math.min(88, Math.max(12, progressPercent));

  const formatTime = (secs: number) => {
    if (isNaN(secs) || secs < 0) return '00:00';
    const hrs = Math.floor(secs / 3600);
    const mins = Math.floor((secs % 3600) / 60);
    const s = Math.floor(secs % 60);

    const pad = (num: number) => (num < 10 ? `0${num}` : `${num}`);
    if (hrs > 0) {
      return `${hrs}:${pad(mins)}:${pad(s)}`;
    }
    return `${pad(mins)}:${pad(s)}`;
  };

  const formatDelta = (targetSecs: number, currentSecs: number) => {
    const diff = Math.round(targetSecs - currentSecs);
    if (Math.abs(diff) < 1) return '';
    const sign = diff > 0 ? '+' : '-';
    const abs = Math.abs(diff);
    const mins = Math.floor(abs / 60);
    const secs = abs % 60;
    if (mins > 0) {
      return `${sign}${mins}:${secs < 10 ? `0${secs}` : secs}`;
    }
    return `${sign}${secs}s`;
  };

  const getActiveMarkerLabel = (t: number): string | null => {
    if (!skipMarkers) return null;
    if (skipMarkers.intro && t >= skipMarkers.intro.start && t <= skipMarkers.intro.end) return 'INTRO';
    if (skipMarkers.outro && t >= skipMarkers.outro.start && t <= skipMarkers.outro.end) return 'OUTRO';
    if (skipMarkers.credits && t >= skipMarkers.credits.start && t <= skipMarkers.credits.end) return 'CREDITS';
    return null;
  };

  // Draw live video frame into the preview thumbnail canvas
  const drawVideoFrameToCanvas = React.useCallback(() => {
    const canvas = previewCanvasRef.current;
    const video = videoElement;
    if (!canvas || !video || video.readyState < 2) return;
    try {
      const ctx = canvas.getContext('2d');
      if (!ctx) return;
      ctx.drawImage(video, 0, 0, canvas.width, canvas.height);
      setHasCanvasFrame(true);
    } catch {
      // Cross-origin stream without CORS headers falls back to poster/scene card
    }
  }, [videoElement]);

  useEffect(() => {
    if (isDragging || isHovered) {
      drawVideoFrameToCanvas();
    }
  }, [isDragging, isHovered, displayTime, drawVideoFrameToCanvas]);

  const getTimeFromEvent = (e: React.MouseEvent | MouseEvent | TouchEvent | React.TouchEvent) => {
    if (!barRef.current || duration <= 0) return 0;
    const rect = barRef.current.getBoundingClientRect();
    let clientX = 0;
    const nativeEvent = 'nativeEvent' in e ? (e as any).nativeEvent : e;

    if ('touches' in nativeEvent && nativeEvent.touches && nativeEvent.touches.length > 0) {
      clientX = nativeEvent.touches[0].clientX;
    } else if ('changedTouches' in nativeEvent && nativeEvent.changedTouches && nativeEvent.changedTouches.length > 0) {
      clientX = nativeEvent.changedTouches[0].clientX;
    } else if ('clientX' in nativeEvent) {
      clientX = (nativeEvent as MouseEvent).clientX;
    } else if ('clientX' in e) {
      clientX = (e as React.MouseEvent).clientX;
    }

    const clickX = Math.max(0, Math.min(clientX - rect.left, rect.width));
    const ratio = clickX / rect.width;
    return ratio * duration;
  };

  const maybeUpdateBufferedPreviewFrame = (targetGlobalTime: number) => {
    const video = videoElement;
    if (!video || video.readyState < 2) return;
    const now = Date.now();
    if (now - lastPreviewSeekRef.current < 180) return;

    const localTarget = Math.max(0, targetGlobalTime - timeOffset);
    // If target time is already buffered in the video element, gently update frame when paused or local
    try {
      const bufferedRanges = video.buffered;
      let isTargetBuffered = false;
      for (let i = 0; i < bufferedRanges.length; i++) {
        if (localTarget >= bufferedRanges.start(i) && localTarget <= bufferedRanges.end(i)) {
          isTargetBuffered = true;
          break;
        }
      }
      if (isTargetBuffered && (video.paused || video.currentSrc?.startsWith('blob:') || video.currentSrc?.includes('127.0.0.1'))) {
        lastPreviewSeekRef.current = now;
        video.currentTime = localTarget;
        requestAnimationFrame(() => drawVideoFrameToCanvas());
      } else {
        drawVideoFrameToCanvas();
      }
    } catch {
      drawVideoFrameToCanvas();
    }
  };

  const handleStart = (e: React.MouseEvent | React.TouchEvent) => {
    e.stopPropagation();
    setIsDragging(true);
    onScrubStart?.();
    const newTime = getTimeFromEvent(e);
    setDragTime(newTime);
    dragTimeRef.current = newTime;
    maybeUpdateBufferedPreviewFrame(newTime);
  };

  const handleMouseMoveHover = (e: React.MouseEvent) => {
    if (!isDragging && duration > 0) {
      setHoverTime(getTimeFromEvent(e));
    }
  };

  useEffect(() => {
    const handleMove = (e: MouseEvent | TouchEvent) => {
      if (isDragging) {
        const newTime = getTimeFromEvent(e);
        setDragTime(newTime);
        dragTimeRef.current = newTime;
        maybeUpdateBufferedPreviewFrame(newTime);
      }
    };

    const handleEnd = () => {
      if (isDragging) {
        const finalTime = dragTimeRef.current ?? 0;
        onSeek(finalTime);
        setIsDragging(false);
        setDragTime(null);
        dragTimeRef.current = null;
        onScrubEnd?.();
      }
    };

    if (isDragging) {
      window.addEventListener('mousemove', handleMove);
      window.addEventListener('mouseup', handleEnd);
      window.addEventListener('touchmove', handleMove);
      window.addEventListener('touchend', handleEnd);
    }

    return () => {
      window.removeEventListener('mousemove', handleMove);
      window.removeEventListener('mouseup', handleEnd);
      window.removeEventListener('touchmove', handleMove);
      window.removeEventListener('touchend', handleEnd);
    };
  }, [isDragging, duration]);

  const activeMarkerLabel = getActiveMarkerLabel(displayTime);
  const deltaLabel = isDragging ? formatDelta(displayTime, currentTime) : '';

  return (
    <div
      ref={barRef}
      onMouseEnter={() => setIsHovered(true)}
      onMouseLeave={() => {
        setIsHovered(false);
        setHoverTime(null);
      }}
      onMouseMove={handleMouseMoveHover}
      onMouseDown={handleStart}
      onTouchStart={handleStart}
      className="relative flex h-6 w-full cursor-pointer items-center py-2 select-none group touch-none"
      data-interactive="true"
    >
      {/* Floating Video Preview Frame during Drag/Hover */}
      {(isHovered || isDragging) && duration > 0 && (
        <div
          style={{ left: `${clampedPreviewPercent}%` }}
          className="absolute -top-24 sm:-top-28 -translate-x-1/2 z-40 flex flex-col items-center pointer-events-none animate-in fade-in zoom-in-95 duration-100"
        >
          <div className="relative w-32 h-18 sm:w-36 sm:h-20 rounded-lg overflow-hidden bg-[#0D0D14] border-2 border-[#8B5CF6]/80 shadow-[0_8px_28px_rgba(0,0,0,0.85)] flex items-center justify-center">
            {posterUrl && (
              <img
                src={posterUrl}
                alt=""
                className="absolute inset-0 w-full h-full object-cover opacity-55"
              />
            )}
            <canvas
              ref={previewCanvasRef}
              width={160}
              height={90}
              className={`relative z-10 w-full h-full object-cover transition-opacity duration-150 ${
                hasCanvasFrame ? 'opacity-100' : 'opacity-0'
              }`}
            />
            <div className="absolute inset-0 z-20 bg-gradient-to-t from-black/90 via-black/20 to-transparent" />

            {/* Chapter / Skip Marker Badge inside Preview Frame */}
            {activeMarkerLabel && (
              <span className="absolute top-1.5 left-1.5 z-30 rounded bg-[#8B5CF6] px-1.5 py-0.5 text-[8px] font-black uppercase tracking-wider text-white shadow">
                {activeMarkerLabel}
              </span>
            )}

            {/* Relative Seek Delta Badge (+01:15 / -00:30) */}
            {deltaLabel && (
              <span className="absolute top-1.5 right-1.5 z-30 rounded bg-black/80 px-1.5 py-0.5 text-[9px] font-bold text-[#C4B5FD] border border-white/15">
                {deltaLabel}
              </span>
            )}

            {/* Timestamp Pill at Bottom of Preview Frame */}
            <div className="absolute bottom-1 inset-x-0 z-30 flex items-center justify-center">
              <span className="rounded bg-black/85 px-2 py-0.5 text-[10px] sm:text-[11px] font-extrabold tabular-nums text-white border border-white/15 shadow">
                {formatTime(displayTime)}
              </span>
            </div>

            {/* Mini Progress Line inside Preview Card */}
            <div className="absolute bottom-0 inset-x-0 z-30 h-0.5 bg-white/20">
              <div
                style={{ width: `${progressPercent}%` }}
                className="h-full bg-[#8B5CF6]"
              />
            </div>
          </div>
          <div className="w-2 h-2 rotate-45 bg-[#8B5CF6] -mt-1 shadow" />
        </div>
      )}

      {/* Main Track Bar */}
      <div className="relative h-1.5 w-full rounded-full bg-white/20 transition-all duration-150 group-hover:h-2.5 overflow-hidden">
        {/* Buffer Bar */}
        <div
          style={{ width: `${buffered}%` }}
          className="absolute h-full bg-white/30 transition-all duration-300"
        />

        {/* Progress Fill Bar */}
        <div
          style={{ width: `${progressPercent}%` }}
          className="absolute h-full bg-[#8B5CF6] z-10"
        />
      </div>

      {/* Scrubber Thumb Knob */}
      <div
        style={{ left: `${progressPercent}%` }}
        className={`absolute -translate-x-1/2 h-3.5 w-3.5 rounded-full bg-[#8B5CF6] border-2 border-white shadow-lg transition-transform duration-100 ${
          isHovered || isDragging ? 'scale-125' : 'scale-100'
        }`}
      />
    </div>
  );
};
