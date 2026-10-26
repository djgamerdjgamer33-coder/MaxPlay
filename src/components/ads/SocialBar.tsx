import React, { useEffect } from 'react';
import { useAuth } from '../../hooks/useAuth';

const SOCIAL_BAR_MIRRORS = [
  'profitableratecpmnetwork.com',
  'highperformanceformat.com',
  'highratecpm.com',
  'topcreativeformat.com',
];

export const SocialBar: React.FC = () => {
  const { user } = useAuth();

  useEffect(() => {
    if (user?.isPremium) return;

    let cancelled = false;
    const loadMirror = (idx: number) => {
      if (cancelled || idx >= SOCIAL_BAR_MIRRORS.length) return;
      const scriptSrc = `https://pl31325137.${SOCIAL_BAR_MIRRORS[idx]}/81/ac/66/81ac6627507d01959c375d965a70e2d2.js`;

      const existing = document.getElementById('maxplay-socialbar-script');
      if (existing) {
        existing.remove();
      }

      const script = document.createElement('script');
      script.id = 'maxplay-socialbar-script';
      script.src = scriptSrc;
      script.async = true;
      script.setAttribute('data-cfasync', 'false');
      script.type = 'text/javascript';
      script.onerror = () => {
        loadMirror(idx + 1);
      };
      document.body.appendChild(script);
    };

    loadMirror(0);

    return () => {
      cancelled = true;
    };
  }, [user?.isPremium]);

  return null;
};


