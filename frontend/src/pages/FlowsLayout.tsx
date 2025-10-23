import { NavLink, Outlet, useLocation } from 'react-router-dom';
import { useMemo } from 'react';
import './FlowsLayout.css';

type Crumb = {
  label: string;
  to?: string;
};

const SEGMENT_LABELS: Record<string, string> = {
  flows: 'Flows',
  launch: 'Launch',
  definitions: 'Definitions',
  sessions: 'Sessions',
};

const formatSegmentLabel = (segment: string) => {
  if (SEGMENT_LABELS[segment]) {
    return SEGMENT_LABELS[segment];
  }
  if (segment.length <= 12) {
    return segment;
  }
  return `${segment.slice(0, 6)}…${segment.slice(-4)}`;
};

const FlowsLayout = () => {
  const location = useLocation();

  const crumbs = useMemo<Crumb[]>(() => {
    const pathname = location.pathname.replace(/\/$/, '');
    const segments = pathname.split('/').filter(Boolean);
    const items: Crumb[] = [];

    let currentPath = '';
    segments.forEach((segment, index) => {
      currentPath += `/${segment}`;
      const isLast = index === segments.length - 1;
      items.push({
        label: formatSegmentLabel(segment),
        to: isLast ? undefined : currentPath,
      });
    });

    if (items.length === 0) {
      return [{ label: 'Flows' }];
    }
    return items;
  }, [location.pathname]);

  return (
    <div className="flows-layout">
      <div className="flows-layout__header">
        <nav className="flows-breadcrumbs" aria-label="Навигация по разделу Flows">
          {crumbs.map((crumb, index) => (
            <span key={`${crumb.label}-${index}`} className="flows-breadcrumbs__item">
              {crumb.to ? <NavLink to={crumb.to}>{crumb.label}</NavLink> : crumb.label}
              {index < crumbs.length - 1 && <span className="flows-breadcrumbs__separator">/</span>}
            </span>
          ))}
        </nav>
        <div className="flows-layout__tabs">
          <NavLink
            to="/flows/launch"
            className={({ isActive }) =>
              `flows-layout__tab ${isActive ? 'flows-layout__tab--active' : ''}`
            }
          >
            Запуск
          </NavLink>
          <NavLink
            to="/flows/sessions"
            className={({ isActive }) =>
              `flows-layout__tab ${isActive ? 'flows-layout__tab--active' : ''}`
            }
          >
            Сессии
          </NavLink>
          <NavLink
            to="/flows/definitions"
            className={({ isActive }) =>
              `flows-layout__tab ${isActive ? 'flows-layout__tab--active' : ''}`
            }
          >
            Определения
          </NavLink>
        </div>
      </div>
      <div className="flows-layout__content">
        <Outlet />
      </div>
    </div>
  );
};

export default FlowsLayout;
