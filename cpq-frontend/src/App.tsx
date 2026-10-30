import React, { useEffect, useMemo } from 'react';
import { RouterProvider } from 'react-router-dom';
import { ConfigProvider, theme as antTheme } from 'antd';
import type { ThemeConfig } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import router from './router';
import { useThemeStore } from './stores/themeStore';

function buildAppTheme(mode: string): ThemeConfig {
  return {
    algorithm: mode === 'dark' ? antTheme.darkAlgorithm : antTheme.defaultAlgorithm,
    token: {
      colorPrimary: '#667eea',
    },
  };
}

/**
 * Single source of the app-wide antd locale/theme. Used both by the root tree and by the
 * holder of antd static methods (message / notification / Modal.confirm), so both follow
 * the same theme. It subscribes to the theme store itself, so a dark/light toggle re-renders
 * the static-method holder too — no re-config needed.
 */
const AppConfigProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const mode = useThemeStore((s) => s.mode);
  const themeConfig = useMemo(() => buildAppTheme(mode), [mode]);
  return (
    <ConfigProvider locale={zhCN} theme={themeConfig}>
      {children}
    </ConfigProvider>
  );
};

// task-261030 F-8: static methods render under the same provider (antd only warns
// "Static function can not consume context like dynamic theme" when holderRender is unset).
ConfigProvider.config({
  holderRender: (children) => <AppConfigProvider>{children}</AppConfigProvider>,
});

const App: React.FC = () => {
  const mode = useThemeStore((s) => s.mode);

  // Sync body data-theme attribute for global CSS
  useEffect(() => {
    document.body.setAttribute('data-theme', mode);
  }, [mode]);

  return (
    <AppConfigProvider>
      <RouterProvider router={router} />
    </AppConfigProvider>
  );
};

export default App;
