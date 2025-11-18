import { useMemo, useState } from 'react';
import type { FormEvent } from 'react';
import {
  fetchProfileDocument,
  type UserProfileDocument,
  updateProfileDocument,
} from '../lib/apiClient';
import './ProfileSettings.css';

const communicationModes = ['TEXT', 'VOICE', 'HYBRID'];

const ProfileSettings = () => {
  const [namespace, setNamespace] = useState('web');
  const [reference, setReference] = useState('demo');
  const [channel, setChannel] = useState('web');
  const profileKey = useMemo(() => `${namespace}:${reference}`, [namespace, reference]);

  const [profile, setProfile] = useState<UserProfileDocument | null>(null);
  const [etag, setEtag] = useState<string | undefined>();
  const [form, setForm] = useState({
    displayName: '',
    locale: 'en',
    timezone: 'UTC',
    communicationMode: 'TEXT',
    habits: '',
    antiPatterns: '',
    workHours: '{\n  "timezone": "UTC"\n}',
    metadata: '{\n  "notes": ""\n}',
  });
  const [status, setStatus] = useState<string>('');
  const [isLoading, setLoading] = useState(false);

  const loadProfile = async () => {
    setLoading(true);
    setStatus('Загрузка профиля…');
    try {
      const { profile: data, etag: nextEtag } = await fetchProfileDocument(
        namespace,
        reference,
        profileKey,
        channel,
      );
      setProfile(data);
      setEtag(nextEtag);
      setForm({
        displayName: data.displayName ?? '',
        locale: data.locale ?? 'en',
        timezone: data.timezone ?? 'UTC',
        communicationMode: data.communicationMode ?? 'TEXT',
        habits: (data.habits ?? []).join(', '),
        antiPatterns: (data.antiPatterns ?? []).join(', '),
        workHours: JSON.stringify(data.workHours ?? {}, null, 2),
        metadata: JSON.stringify(data.metadata ?? {}, null, 2),
      });
      setStatus('Профиль загружен');
    } catch (error) {
      setStatus(error instanceof Error ? error.message : 'Не удалось загрузить профиль');
    } finally {
      setLoading(false);
    }
  };

  const handleSubmit = async (event: FormEvent) => {
    event.preventDefault();
    if (!profile) {
      setStatus('Сначала загрузите профиль');
      return;
    }
    setLoading(true);
    setStatus('Сохранение…');
    try {
      const payload = {
        displayName: form.displayName.trim(),
        locale: form.locale.trim(),
        timezone: form.timezone.trim(),
        communicationMode: form.communicationMode as string,
        habits: form.habits
          .split(',')
          .map((value) => value.trim())
          .filter(Boolean),
        antiPatterns: form.antiPatterns
          .split(',')
          .map((value) => value.trim())
          .filter(Boolean),
        workHours: JSON.parse(form.workHours || '{}'),
        metadata: JSON.parse(form.metadata || '{}'),
        channelOverrides: [],
      };
      const { profile: updated, etag: nextEtag } = await updateProfileDocument(
        namespace,
        reference,
        profileKey,
        payload,
        { channel, ifMatch: etag },
      );
      setProfile(updated);
      setEtag(nextEtag);
      setStatus('Профиль сохранён');
    } catch (error) {
      if (error instanceof SyntaxError) {
        setStatus('Некорректный JSON в Work hours или Metadata');
      } else {
        setStatus(error instanceof Error ? error.message : 'Не удалось сохранить');
      }
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="profile-settings">
      <h2>Personalization</h2>
      <section className="profile-form">
        <div className="profile-key-row">
          <label>
            Namespace
            <input value={namespace} onChange={(e) => setNamespace(e.target.value)} />
          </label>
          <label>
            Reference
            <input value={reference} onChange={(e) => setReference(e.target.value)} />
          </label>
          <label>
            Channel
            <input value={channel} onChange={(e) => setChannel(e.target.value)} />
          </label>
          <button type="button" onClick={loadProfile} disabled={isLoading}>
            Загрузить
          </button>
        </div>
      </section>

      <form onSubmit={handleSubmit} className="profile-edit-form">
        <label>
          Display name
          <input
            value={form.displayName}
            onChange={(e) => setForm({ ...form, displayName: e.target.value })}
          />
        </label>
        <label>
          Locale
          <input value={form.locale} onChange={(e) => setForm({ ...form, locale: e.target.value })} />
        </label>
        <label>
          Timezone
          <input
            value={form.timezone}
            onChange={(e) => setForm({ ...form, timezone: e.target.value })}
          />
        </label>
        <label>
          Communication mode
          <select
            value={form.communicationMode}
            onChange={(e) => setForm({ ...form, communicationMode: e.target.value })}
          >
            {communicationModes.map((mode) => (
              <option key={mode} value={mode}>
                {mode}
              </option>
            ))}
          </select>
        </label>
        <label>
          Habits (через запятую)
          <input
            value={form.habits}
            onChange={(e) => setForm({ ...form, habits: e.target.value })}
          />
        </label>
        <label>
          Anti-patterns (через запятую)
          <input
            value={form.antiPatterns}
            onChange={(e) => setForm({ ...form, antiPatterns: e.target.value })}
          />
        </label>

        <label>
          Work hours (JSON)
          <textarea
            value={form.workHours}
            onChange={(e) => setForm({ ...form, workHours: e.target.value })}
            rows={6}
          />
        </label>
        <label>
          Metadata (JSON)
          <textarea
            value={form.metadata}
            onChange={(e) => setForm({ ...form, metadata: e.target.value })}
            rows={6}
          />
        </label>

        <button type="submit" disabled={isLoading || !profile}>
          Сохранить
        </button>
        <p className="profile-status">{status}</p>
      </form>

      {profile && (
        <section className="profile-meta">
          <h3>Current profile</h3>
          <p>
            ID: <code>{profile.profileId}</code>
          </p>
          <p>Роли: {profile.roles.join(', ') || '—'}</p>
          <div>
            <h4>Identities</h4>
            {profile.identities.length === 0 ? (
              <p>Нет привязанных identity</p>
            ) : (
              <ul>
                {profile.identities.map((identity) => (
                  <li key={`${identity.provider}:${identity.externalId}`}>
                    {identity.provider}: {identity.externalId}
                  </li>
                ))}
              </ul>
            )}
          </div>
        </section>
      )}
    </div>
  );
};

export default ProfileSettings;
