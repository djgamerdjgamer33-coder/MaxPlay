import React, { useEffect, useRef } from 'react';
import { useAuth } from '../../hooks/useAuth';

interface NativeBannerProps {
  className?: string;
}

export const NativeBanner: React.FC<NativeBannerProps> = ({ className = '' }) => {
  const { user } = useAuth();
  const iframeRef = useRef<HTMLIFrameElement>(null);
  const [hasAdLoaded, setHasAdLoaded] = React.useState<boolean>(true);

  useEffect(() => {
    if (user?.isPremium || !iframeRef.current) return;

    const onMessage = (e: MessageEvent) => {
      if (e.data === 'MAXPLAY_NATIVE_BANNER_EMPTY') {
        setHasAdLoaded(false);
      } else if (e.data === 'MAXPLAY_NATIVE_BANNER_READY') {
        setHasAdLoaded(true);
      }
    };
    window.addEventListener('message', onMessage);

    // Use a small delay to ensure iframe is ready in the DOM before writing to it
    const timer = setTimeout(() => {
      const iframe = iframeRef.current;
      if (!iframe) return;
      
      const doc = iframe.contentWindow?.document;
      if (doc) {
        doc.open();
        doc.write(`
          <!DOCTYPE html>
          <html>
            <head>
              <meta name="viewport" content="width=device-width, initial-scale=1.0">
              <style>
                body { margin: 0; padding: 0; overflow: hidden; display: flex; justify-content: center; align-items: center; background: transparent; }
              </style>
            </head>
            <body>
              <div id="container-06b7881a58031e798d9d74f29f7f7f86"></div>
              <script type="text/javascript">
                (function() {
                  var mirrors = [
                    "profitableratecpmnetwork.com",
                    "highperformanceformat.com",
                    "highratecpm.com",
                    "topcreativeformat.com"
                  ];
                  var idx = 0;
                  function tryLoad() {
                    if (idx >= mirrors.length) {
                      try { window.parent.postMessage("MAXPLAY_NATIVE_BANNER_EMPTY", "*"); } catch (_) {}
                      return;
                    }
                    var s = document.createElement("script");
                    s.type = "text/javascript";
                    s.async = true;
                    s.setAttribute("data-cfasync", "false");
                    s.src = "https://pl31325139." + mirrors[idx] + "/06b7881a58031e798d9d74f29f7f7f86/invoke.js";
                    idx++;
                    s.onload = function() {
                      setTimeout(function() {
                        var c = document.getElementById("container-06b7881a58031e798d9d74f29f7f7f86");
                        if (c && c.innerHTML.trim().length > 0) {
                          try { window.parent.postMessage("MAXPLAY_NATIVE_BANNER_READY", "*"); } catch (_) {}
                        } else if (idx < mirrors.length) {
                          tryLoad();
                        }
                      }, 1200);
                    };
                    s.onerror = function() {
                      tryLoad();
                    };
                    document.body.appendChild(s);
                  }
                  tryLoad();
                })();
              </script>
            </body>
          </html>
        `);
        doc.close();
      }
    }, 50);

    return () => {
      clearTimeout(timer);
      window.removeEventListener('message', onMessage);
    };
  }, [user?.isPremium]);

  if (user?.isPremium || !hasAdLoaded) return null;

  return (
    <div className={`w-full flex justify-center items-center overflow-hidden my-2 min-h-[60px] ${className}`}>
      <iframe
        ref={iframeRef}
        title="Advertisement"
        sandbox="allow-scripts allow-same-origin allow-popups allow-popups-to-escape-sandbox"
        style={{ width: '100%', height: '90px', border: 'none', overflow: 'hidden' }}
        scrolling="no"
      />
    </div>
  );
};


