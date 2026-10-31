import React from 'react';
import { SectionHeader } from '../common/SectionHeader';
import { NetworkItem } from '../../types';
import { DEFAULT_NETWORK_PRESETS } from '../../utils/networkPresets';
import { Tv, Sparkles, ChevronRight } from 'lucide-react';

interface NetworkGridRowProps {
  title?: string;
  icon?: React.ReactNode;
  networks?: NetworkItem[];
  onSelectNetwork: (network: NetworkItem) => void;
  onSeeAll?: () => void;
}

export const NetworkGridRow: React.FC<NetworkGridRowProps> = ({
  title = 'Networks',
  icon,
  networks,
  onSelectNetwork,
  onSeeAll,
}) => {
  const activeNetworks = (networks && networks.length > 0)
    ? networks
    : DEFAULT_NETWORK_PRESETS;

  return (
    <div className="mt-5 px-4">
      <SectionHeader
        title={title}
        icon={icon || <Tv className="h-5 w-5 text-[#06B6D4]" />}
        onActionPress={onSeeAll}
      />

      <div className="grid grid-cols-4 sm:grid-cols-4 md:grid-cols-6 lg:grid-cols-8 gap-2.5 sm:gap-3.5 pt-1">
        {activeNetworks.map((net, index) => {
          const logoSrc = net.logoUrl || net.bannerUrl || '';
          const networkName = net.name || 'Network';
          const customHex =
            net.brandColor && /^#[0-9a-fA-F]{3,6}$/.test(net.brandColor.trim())
              ? net.brandColor.trim()
              : undefined;

          return (
            <button
              key={net.id || `net-${index}`}
              type="button"
              onClick={() => onSelectNetwork(net)}
              className="group relative flex flex-col items-center justify-center p-2.5 sm:p-3 rounded-2xl bg-gradient-to-b from-[#18181F] to-[#0E0E12] border border-white/10 hover:border-[#8B5CF6]/60 shadow-[0_4px_16px_rgba(0,0,0,0.5)] hover:shadow-[0_6px_20px_rgba(139,92,246,0.25)] hover:scale-[1.04] active:scale-95 transition-all duration-200 cursor-pointer overflow-hidden text-center aspect-square"
              style={
                customHex
                  ? {
                      borderColor: `${customHex}55`,
                      boxShadow: `0 6px 20px -4px ${customHex}40`,
                    }
                  : undefined
              }
              title={networkName}
            >
              {/* Subtle Ambient Hover Glow */}
              <div
                className="absolute inset-0 bg-gradient-to-tr from-[#8B5CF6]/0 via-[#06B6D4]/0 to-[#8B5CF6]/10 opacity-0 group-hover:opacity-100 transition-opacity duration-300 pointer-events-none"
                style={
                  customHex
                    ? {
                        background: `radial-gradient(circle at center, ${customHex}30 0%, transparent 75%)`,
                        opacity: 0.75,
                      }
                    : undefined
                }
              />

              {/* Logo Container */}
              <div className="relative w-full h-[60%] flex items-center justify-center">
                {logoSrc ? (
                  <img
                    src={logoSrc}
                    alt={networkName}
                    className="max-h-full max-w-full object-contain filter drop-shadow-[0_2px_8px_rgba(0,0,0,0.6)] group-hover:scale-110 transition-transform duration-300 rounded-lg"
                    onError={(e) => {
                      (e.target as HTMLElement).style.display = 'none';
                    }}
                  />
                ) : (
                  <div
                    className="flex h-10 w-10 items-center justify-center rounded-xl font-black text-sm text-white shadow-inner"
                    style={{
                      background: customHex
                        ? `linear-gradient(135deg, ${customHex}, #1E1B4B)`
                        : 'linear-gradient(135deg, #8B5CF6, #06B6D4)',
                    }}
                  >
                    {networkName.slice(0, 2).toUpperCase()}
                  </div>
                )}
              </div>

              {/* Network Name Label */}
              <span className="mt-1.5 w-full text-[10.5px] sm:text-[11px] font-bold text-[#E4E4E7] group-hover:text-white truncate tracking-tight transition-colors">
                {networkName}
              </span>
            </button>
          );
        })}
      </div>
    </div>
  );
};
