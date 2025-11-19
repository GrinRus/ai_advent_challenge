import { useEffect, useMemo, useState } from 'react';
import {
  createDevLink,
  loadActiveProfile,
  loadActiveProfileAudit,
} from '../lib/profileActions';
import { buildProfileKey, useProfileStore } from '../lib/profileStore';
import type {
  DevProfileLinkResponse,
  ProfileAuditEntry,
  UserProfileDocument,
} from '../lib/profileTypes';
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
  const devToken = useProfileStore((state) => state.devToken);
  const setDevToken = useProfileStore((state) => state.setDevToken);
  const auditEvents = useProfileStore((state) => state.auditEvents);
  const isAuditLoading = useProfileStore((state) => state.isAuditLoading);
  const auditError = useProfileStore((state) => state.auditError);
  const isLoading = useProfileStore((state) => state.isLoading);
  const [bannerStatus, setBannerStatus] = useState<string>('');
  const [initialLoadRequested, setInitialLoadRequested] = useState(false);
  const [devTokenDraft, setDevTokenDraft] = useState(devToken ?? '');
  const [devLink, setDevLink] = useState<DevProfileLinkResponse | null>(null);
  const [isGeneratingLink, setGeneratingLink] = useState(false);
  const profileKey = buildProfileKey(namespace, reference);

  useEffect(() => {
    setDevTokenDraft(devToken ?? '');
  }, [devToken]);

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

  const describeEvent = (entry: ProfileAuditEntry) => {
    const metadata = (entry.metadata ?? {}) as Record<string, unknown>;
    switch (entry.eventType) {
      case 'profile_created':
        return `Профиль создан (v${metadata.version ?? '—'})`;
      case 'profile_updated':
        return `Профиль обновлён (v${metadata.version ?? '—'})`;
      case 'identity_attached':
        return `Привязана учётная запись ${metadata.provider ?? ''}`.trim();
      case 'identity_detached':
        return `Откреплена учётная запись ${metadata.provider ?? ''}`.trim();
      case 'role_assigned':
        return `Выдана роль ${metadata.role ?? ''}`.trim();
      case 'role_revoked':
        return `Снята роль ${metadata.role ?? ''}`.trim();
      default:
        return entry.eventType;
    }
  };

  const renderAuditItem = (entry: ProfileAuditEntry) => (
    <li key={entry.id}>
      <span className="profile-banner-history-time">{formatTimestamp(entry.createdAt)}</span>
      <span className="profile-banner-history-source">{describeEvent(entry)}</span>
      {entry.channel && <span className="profile-banner-history-channel"> · {entry.channel}</span>}
    </li>
  );

  const handleReload = async () => {
    setBannerStatus('Обновляем профиль…');
    try {
      await loadActiveProfile();
      await loadActiveProfileAudit();
      setBannerStatus('Профиль и журнал обновлены');
    } catch (error) {
      setBannerStatus(
        error instanceof Error
          ? error.message
          : 'Не удалось обновить профиль',
      );
    }
  };

  const handleSaveDevToken = () => {
    setDevToken(devTokenDraft || undefined);
    setBannerStatus('Dev token обновлён');
  };

  const handleGenerateDevLink = async () => {
    if (!devToken) {
      setBannerStatus('Укажите dev token, чтобы сгенерировать ссылку');
      return;
    }
    setGeneratingLink(true);
    setBannerStatus('Создаём dev-link…');
    try {
      const link = await createDevLink();
      setDevLink(link);
      setBannerStatus('Dev-link сгенерирован');
    } catch (error) {
      setBannerStatus(
        error instanceof Error
          ? error.message
          : 'Не удалось создать dev-link',
      );
    } finally {
      setGeneratingLink(false);
    }
  };

  const renderDevLink = () => {
    if (!devLink) {
      return null;
    }
    const expires = new Date(devLink.expiresAt).toLocaleString();
    return (
      <p className="profile-dev-link-result">
        Код: <code>{devLink.code}</code> · канал{' '}
        {devLink.channel ?? 'any'} · истекает {expires}
      </p>
    );
  };

  useEffect(() => {
    if (profile || initialLoadRequested) {
      return;
    }
    setInitialLoadRequested(true);
    loadActiveProfile().catch(() => {
      setBannerStatus('Не удалось загрузить профиль, попробуйте ещё раз.');
    });
  }, [profile, initialLoadRequested]);

  useEffect(() => {
    loadActiveProfileAudit().catch(() => {
      setBannerStatus('Не удалось загрузить историю профиля.');
    });
  }, [profileKey, devToken]);

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
      <div className="profile-banner-dev">
        <div className="profile-banner-dev-token">
          <label>
            Dev token
            <input
              value={devTokenDraft}
              onChange={(event) => setDevTokenDraft(event.target.value)}
              placeholder="dev-profile-token"
            />
          </label>
          <button type="button" onClick={handleSaveDevToken}>
            Сохранить token
          </button>
          <span className="profile-banner-dev-note">
            {devToken ? 'Dev session активна' : 'Введите токен из PROFILE_BASIC_TOKEN'}
          </span>
        </div>
        <div className="profile-banner-dev-link">
          <button
            type="button"
            onClick={handleGenerateDevLink}
            disabled={!devToken || isGeneratingLink}
          >
            {isGeneratingLink ? 'Создание…' : 'Создать dev-link'}
          </button>
          <p>
            Dev-link выдаёт одноразовый код для Telegram/CLI. Доступно только в dev-режиме и
            действует ограниченное время.
          </p>
          {renderDevLink()}
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
            {isAuditLoading ? (
              <p>Загружаем журнал…</p>
            ) : auditError ? (
              <p>{auditError}</p>
            ) : auditEvents.length === 0 ? (
              <p>Журнал пуст — профиль ещё не обновляли.</p>
            ) : (
              <ul>{auditEvents.slice(0, 5).map(renderAuditItem)}</ul>
            )}
          </div>
        </div>
      ) : (
        <div className="profile-banner-empty">
          <p>Профиль ещё не загружен. Нажмите «Обновить», чтобы получить данные.</p>
        </div>
      )}
      {bannerStatus && (
        <p className="profile-banner-status">{bannerStatus}</p>
      )}
    </section>
  );
};

export default ActiveProfileBanner;
