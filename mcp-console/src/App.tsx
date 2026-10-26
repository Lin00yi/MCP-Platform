import { lazy, Suspense } from "react";
import { Navigate, Outlet, Route, Routes, useLocation } from "react-router-dom";
import { AppLayout } from "@/components/layout/AppLayout";
import { LoadingState } from "@/components/PageState";
import { LoginPage } from "@/pages/LoginPage";
import { isLoggedIn, roleLevel } from "@/lib/auth";

/** 路由级角色门槛：与侧边栏 minLevel 保持一致，防止直接输 URL 越权访问页面 */
function RequireLevel({ min, children }: { min: number; children: React.ReactNode }) {
  if (roleLevel() < min) {
    return <Navigate to="/dashboard" replace />;
  }
  return <>{children}</>;
}

// 路由级代码分割：各页面按需加载，减小首屏体积
const DashboardPage = lazy(() => import("@/pages/DashboardPage").then((m) => ({ default: m.DashboardPage })));
const TenantsPage = lazy(() => import("@/pages/TenantsPage").then((m) => ({ default: m.TenantsPage })));
const ServersPage = lazy(() => import("@/pages/ServersPage").then((m) => ({ default: m.ServersPage })));
const ServerDetailPage = lazy(() => import("@/pages/ServerDetailPage").then((m) => ({ default: m.ServerDetailPage })));
const CatalogPage = lazy(() => import("@/pages/CatalogPage").then((m) => ({ default: m.CatalogPage })));
const NamespacesPage = lazy(() => import("@/pages/NamespacesPage").then((m) => ({ default: m.NamespacesPage })));
const EndpointsPage = lazy(() => import("@/pages/EndpointsPage").then((m) => ({ default: m.EndpointsPage })));
const SystemsPage = lazy(() => import("@/pages/SystemsPage").then((m) => ({ default: m.SystemsPage })));
const LogsPage = lazy(() => import("@/pages/LogsPage").then((m) => ({ default: m.LogsPage })));
const AuditLogPage = lazy(() => import("@/pages/AuditLogPage").then((m) => ({ default: m.AuditLogPage })));
const ApprovalsPage = lazy(() => import("@/pages/ApprovalsPage").then((m) => ({ default: m.ApprovalsPage })));
const UsersPage = lazy(() => import("@/pages/UsersPage").then((m) => ({ default: m.UsersPage })));

function Page({ children }: { children: React.ReactNode }) {
  return <Suspense fallback={<LoadingState label="加载页面…" />}>{children}</Suspense>;
}

/** 未登录则跳登录页；带上当前路由，登录成功后回跳 */
function RequireAuth() {
  const location = useLocation();
  if (!isLoggedIn()) {
    const from = `${location.pathname}${location.search}`;
    return <Navigate to={`/login?from=${encodeURIComponent(from)}`} replace />;
  }
  return <Outlet />;
}

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route element={<RequireAuth />}>
        <Route element={<AppLayout />}>
          <Route index element={<Navigate to="/dashboard" replace />} />
          <Route path="/dashboard" element={<Page><DashboardPage /></Page>} />
          <Route path="/tenants" element={<RequireLevel min={4}><Page><TenantsPage /></Page></RequireLevel>} />
          <Route path="/servers" element={<RequireLevel min={2}><Page><ServersPage /></Page></RequireLevel>} />
          <Route path="/servers/:id" element={<RequireLevel min={2}><Page><ServerDetailPage /></Page></RequireLevel>} />
          <Route path="/catalog" element={<Page><CatalogPage /></Page>} />
          <Route path="/namespaces" element={<RequireLevel min={3}><Page><NamespacesPage /></Page></RequireLevel>} />
          <Route path="/endpoints" element={<RequireLevel min={3}><Page><EndpointsPage /></Page></RequireLevel>} />
          <Route path="/systems" element={<RequireLevel min={2}><Page><SystemsPage /></Page></RequireLevel>} />
          <Route path="/logs" element={<Page><LogsPage /></Page>} />
          <Route path="/audit" element={<RequireLevel min={2}><Page><AuditLogPage /></Page></RequireLevel>} />
          <Route path="/approvals" element={<RequireLevel min={2}><Page><ApprovalsPage /></Page></RequireLevel>} />
          <Route path="/users" element={<RequireLevel min={3}><Page><UsersPage /></Page></RequireLevel>} />
          <Route path="*" element={<Navigate to="/dashboard" replace />} />
        </Route>
      </Route>
    </Routes>
  );
}
