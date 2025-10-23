import { NavLink } from 'react-router-dom';
import './FlowsOverview.css';

const FlowsOverview = () => (
  <div className="flows-overview">
    <section className="flows-overview__card">
      <h2>Управление агентными флоу</h2>
      <p>
        В этом разделе можно запускать и контролировать мультиагентные флоу, а также управлять их
        определениями. Используйте вкладки выше, чтобы переключаться между сессиями и библиотекой
        шаблонов.
      </p>
      <div className="flows-overview__actions">
        <NavLink className="primary-btn" to="/flows/launch">
          Настроить запуск
        </NavLink>
        <NavLink className="secondary-btn" to="/flows/sessions">
          Мониторинг сессий
        </NavLink>
        <NavLink className="secondary-btn" to="/flows/definitions">
          Библиотека шаблонов
        </NavLink>
      </div>
    </section>

    <section className="flows-overview__card">
      <h3>Быстрые ссылки</h3>
      <ul className="flows-overview__links">
        <li>
          <NavLink to="/flows/launch">Настройка запуска и параметры</NavLink>
        </li>
        <li>
          <NavLink to="/flows/sessions">Мониторинг активных сессий</NavLink>
        </li>
        <li>
          <NavLink to="/flows/definitions">Публикация и версии определений</NavLink>
        </li>
      </ul>
    </section>
  </div>
);

export default FlowsOverview;
