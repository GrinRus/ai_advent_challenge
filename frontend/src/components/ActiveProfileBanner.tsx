import { useEffect, useMemo, useState } from 'react';
import { loadActiveProfile } from '../lib/profileActions';
import {
  buildProfileKey,
  type ProfileHistoryEntry,
  useProfileStore,
} from '../lib/profileStore';
import type { UserProfileDocument } from '../lib/profileTypes';
import './ActiveProfileBanner.css';

const formatTimestamp = (value?: string) => {
  if (!value) {
    return '—';
  }
  try {
    return new Date(value).toLocaleString();
  } catch (error) {
    return value;
  }
};

const resolveProfileShortInfo = (profile?: UserProfileDocument | null) => {
  if (!profile) {
    return '—';
  }
  const parts = [profile.locale, profile.timezone].filter(Boolean);
  return parts.join(' · ');
};

const ActiveProfileBanner = () => {
  const profile = useProfileStore((state) => state.profile);
  const namespace = useProfileStore((state) => state.namespace);
  const reference = useProfileStore((state) => state.reference);
  const channel = useProfileStore((state) => state.channel);
  const setChannel = useProfileStore((state) => state.setChannel);
  const history = useProfileStore((state) => state.history);
  const isLoading = useProfileStore((state) => state.isLoading);
  const [bannerStatus, setBannerStatus] = useState<string>('');
  const [initialLoadRequested, setInitialLoadRequested] = useState(false);
  const profileKey = buildProfileKey(namespace, reference);

  const availableChannels = useMemo(() => {
    const options = new Set<string>(['web', 'telegram']);
    if (channel) {
      options.add(channel);
    }
    profile?.channels.forEach((entry) => {
      if (entry?.channel) {
        options.add(entry.channel);
      }
    });
    return Array.from(options.values()).sort();
  }, [channel, profile?.channels]);

  const channelOverrides = useMemo(() => {
    if (!profile) {
      return null;
    }
    return profile.channels.find((entry) => entry.channel === channel) ?? null;
  }, [profile, channel]);

  const handleReload = async () => {
    setBannerStatus('Обновляем профиль…');
    try {
      await loadActiveProfile();
      setBannerStatus('Профиль обновлён');
    } catch (error) {
      setBannerStatus(error instanceof Error ? error.message : 'Не удалось обновить профиль');
    }
  };

  const renderHistoryItem = (entry: ProfileHistoryEntry) => (
    <li key={`${entry.timestamp}-${entry.source}`}>
      <span className="profile-banner-history-time">{formatTimestamp(entry.timestamp)}</span>
      <span className="profile-banner-history-source">{entry.source}</span>
      {entry.channel && <span className="profile-banner-history-channel">· {entry.channel}</span>}
      {entry.message && <span className="profile-banner-history-message"> — {entry.message}</span>}
    </li>
  );

  useEffect(() => {
    if (profile || initialLoadRequested) {
      return;
    }
    setInitialLoadRequested(true);
    loadActiveProfile().catch(() => {
      setBannerStatus('Не удалось загрузить профиль, попробуйте ещё раз.');
    });
  }, [profile, initialLoadRequested]);

  return (
    <section className="profile-banner">
      <div className="profile-banner-header">
        <div>
          <p className="profile-banner-title">Активный профиль</p>
          <p className="profile-banner-hint">X-Profile-Key: {profileKey}</p>
        </div>
        <div className="profile-banner-actions">
          <label>
            Канал
            <select value={channel} onChange={(event) => setChannel(event.target.value)}>
              {availableChannels.map((code) => (
                <option key={code} value={code}>
                  {code}
                </option>
              ))}
            </select>
          </label>
          <button type="button" onClick={handleReload} disabled={isLoading}>
            Обновить
          </button>
        </div>
      </div>
      {profile ? (
        <div className="profile-banner-content">
          <div className="profile-banner-summary">
            <p>
              <strong>{profile.displayName}</strong> · {resolveProfileShortInfo(profile)}
            </p>
            <p>
              Роли: {profile.roles.length ? profile.roles.join(', ') : '—'} · Последнее обновление:{' '}
              {formatTimestamp(profile.updatedAt)}
            </p>
            <p className="profile-banner-subtle">
              Единый профиль синхронизируется между каналами. Изменения сохраняются для web,
              Telegram и других клиентов.
            </p>
          </div>
          <div className="profile-banner-overrides">
            <h4>Channel overrides</h4>
            {channelOverrides?.settings ? (
              <pre>{JSON.stringify(channelOverrides.settings, null, 2)}</pre>
            ) : (
              <p>Для канала {channel} нет overrides — используются базовые настройки профиля.</p>
            )}
          </div>
          <div className="profile-banner-history">
            <h4>История</h4>
            {history.length === 0 ? (
              <p>Нет локальных событий. Обновите профиль, чтобы увидеть журнал.</p>
            ) : (
              <ul>{history.slice(0, 3).map(renderHistoryItem)}</ul>
            )}
          </div>
        </div>
      ) : (
        <div className="profile-banner-empty">
          <p>Профиль ещё не загружен. Нажмите «Обновить», чтобы получить данные.</p>
        </div>
      )}
      {bannerStatus && <p className="profile-banner-status">{bannerStatus}</p>}
    </section>
  );
};

export default ActiveProfileBanner;
