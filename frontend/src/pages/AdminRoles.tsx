import { useEffect, useState } from 'react';
import {
  assignProfileRole,
  fetchAdminProfiles,
  fetchAdminRoles,
  fetchProfileAudit,
  revokeProfileRole,
  type AdminRole,
  type ProfileAdminPage,
  type ProfileAuditEntry,
} from '../lib/apiClient';
import { buildProfileKey, useProfileStore } from '../lib/profileStore';
import type { ProfileAdminSummary } from '../lib/profileTypes';
import './AdminRoles.css';

const AdminRoles = () => {
  const [namespaceFilter, setNamespaceFilter] = useState('web');
  const [referenceFilter, setReferenceFilter] = useState('');
  const [page, setPage] = useState(0);
  const [profiles, setProfiles] = useState<ProfileAdminSummary[]>([]);
  const [totalPages, setTotalPages] = useState(0);
  const [isLoading, setLoading] = useState(false);
  const [status, setStatus] = useState('');
  const [availableRoles, setAvailableRoles] = useState<AdminRole[]>([]);
  const [roleSelections, setRoleSelections] = useState<Record<string, string>>({});
  const [auditProfile, setAuditProfile] = useState<ProfileAdminSummary | null>(null);
  const [auditEntries, setAuditEntries] = useState<ProfileAuditEntry[]>([]);
  const [auditStatus, setAuditStatus] = useState('');

  const activeNamespace = useProfileStore((state) => state.namespace);
  const activeReference = useProfileStore((state) => state.reference);
  const activeChannel = useProfileStore((state) => state.channel);
  const devToken = useProfileStore((state) => state.devToken);
  const activeProfileKey = buildProfileKey(activeNamespace, activeReference);

  useEffect(() => {
    fetchAdminRoles()
      .then((data) => setAvailableRoles(data))
      .catch((error) => setStatus(error instanceof Error ? error.message : 'Не удалось загрузить роли'));
  }, []);

  useEffect(() => {
    loadProfiles(0);
  }, []);

  const loadProfiles = async (targetPage: number) => {
    setLoading(true);
    setStatus('Загрузка профилей…');
    try {
      const pageData = await fetchAdminProfiles({
        namespace: namespaceFilter || undefined,
        reference: referenceFilter || undefined,
        page: targetPage,
      });
      setProfiles(pageData.content);
      setTotalPages(pageData.totalPages);
      setPage(pageData.number);
      setStatus(`Найдено ${pageData.totalElements} профилей`);
    } catch (error) {
      setStatus(error instanceof Error ? error.message : 'Не удалось загрузить профили');
    } finally {
      setLoading(false);
    }
  };

  const handleAssignRole = async (profileId: string) => {
    const roleCode = roleSelections[profileId];
    if (!roleCode) {
      setStatus('Выберите роль для назначения');
      return;
    }
    setStatus('Назначение роли…');
    try {
      await assignProfileRole(profileId, roleCode);
      await loadProfiles(page);
      setStatus('Роль назначена');
    } catch (error) {
      setStatus(error instanceof Error ? error.message : 'Ошибка при назначении роли');
    }
  };

  const handleRevokeRole = async (profileId: string, roleCode: string) => {
    setStatus('Снятие роли…');
    try {
      await revokeProfileRole(profileId, roleCode);
      await loadProfiles(page);
      setStatus('Роль снята');
    } catch (error) {
      setStatus(error instanceof Error ? error.message : 'Ошибка при снятии роли');
    }
  };

  const handleViewAudit = async (profile: ProfileAdminSummary) => {
    setAuditStatus('Загрузка истории…');
    setAuditEntries([]);
    setAuditProfile(profile);
    try {
      const entries = await fetchProfileAudit(
        profile.namespace,
        profile.reference,
        activeProfileKey,
        activeChannel,
        devToken,
      );
      setAuditEntries(entries);
      setAuditStatus(entries.length ? '' : 'История пуста');
    } catch (error) {
      setAuditStatus(error instanceof Error ? error.message : 'Не удалось загрузить историю');
    }
  };

  const canGoPrev = page > 0;
  const canGoNext = page < totalPages - 1;

  return (
    <div className="admin-roles">
      <h2>Admin · Roles</h2>
      <div className="admin-roles-filter">
        <label>
          Namespace
          <input value={namespaceFilter} onChange={(event) => setNamespaceFilter(event.target.value)} />
        </label>
        <label>
          Reference
          <input value={referenceFilter} onChange={(event) => setReferenceFilter(event.target.value)} />
        </label>
        <button type="button" onClick={() => loadProfiles(0)} disabled={isLoading}>
          Применить фильтр
        </button>
      </div>

      <div className="admin-roles-status">{status}</div>

      <div className="admin-roles-table-wrapper">
        <table className="admin-roles-table">
          <thead>
            <tr>
              <th>Namespace</th>
              <th>Reference</th>
              <th>Display name</th>
              <th>Roles</th>
              <th>Updated</th>
              <th>Actions</th>
            </tr>
          </thead>
          <tbody>
            {profiles.map((profile) => (
              <tr key={profile.profileId}>
                <td>{profile.namespace}</td>
                <td>{profile.reference}</td>
                <td>{profile.displayName}</td>
                <td>
                  {profile.roles.length === 0 ? (
                    <span className="admin-roles-empty">Нет ролей</span>
                  ) : (
                    profile.roles.map((role) => (
                      <button
                        key={role}
                        type="button"
                        className="role-pill"
                        onClick={() => handleRevokeRole(profile.profileId, role)}
                      >
                        {role}
                        <span aria-hidden="true">×</span>
                      </button>
                    ))
                  )}
                </td>
                <td>{new Date(profile.updatedAt).toLocaleString()}</td>
                <td>
                  <div className="admin-roles-actions">
                    <select
                      value={roleSelections[profile.profileId] ?? ''}
                      onChange={(event) =>
                        setRoleSelections((prev) => ({
                          ...prev,
                          [profile.profileId]: event.target.value,
                        }))
                      }
                    >
                      <option value="">Выберите роль</option>
                      {availableRoles.map((role) => (
                        <option key={role.code} value={role.code}>
                          {role.displayName}
                        </option>
                      ))}
                    </select>
                    <button type="button" onClick={() => handleAssignRole(profile.profileId)}>
                      Назначить
                    </button>
                    <button type="button" onClick={() => handleViewAudit(profile)}>
                      История
                    </button>
                  </div>
                </td>
              </tr>
            ))}
            {!profiles.length && !isLoading && (
              <tr>
                <td colSpan={6} className="admin-roles-empty">
                  Профили не найдены.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

      <div className="admin-roles-pagination">
        <button type="button" onClick={() => loadProfiles(page - 1)} disabled={!canGoPrev || isLoading}>
          Назад
        </button>
        <span>
          Страница {page + 1} из {Math.max(totalPages, 1)}
        </span>
        <button type="button" onClick={() => loadProfiles(page + 1)} disabled={!canGoNext || isLoading}>
          Вперёд
        </button>
      </div>

      <section className="admin-roles-audit">
        <h3>История изменений</h3>
        {auditProfile ? (
          <p>
            {auditProfile.namespace}:{auditProfile.reference}
          </p>
        ) : (
          <p>Выберите профиль, чтобы увидеть журнал.</p>
        )}
        {auditStatus && <p className="admin-roles-status">{auditStatus}</p>}
        {auditEntries.length > 0 && (
          <ul>
            {auditEntries.map((entry) => (
              <li key={entry.id}>
                <span className="audit-time">{new Date(entry.createdAt).toLocaleString()}</span>
                <span className="audit-type">{entry.eventType}</span>
                {entry.channel && <span className="audit-channel"> · {entry.channel}</span>}
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  );
};

export default AdminRoles;
