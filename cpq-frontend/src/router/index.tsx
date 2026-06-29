import React from 'react';
import { createBrowserRouter, Navigate } from 'react-router-dom';
import MainLayout from '../layouts/MainLayout';
import AuthGuard from './AuthGuard';
import Login from '../pages/Login';
import Dashboard from '../pages/Dashboard';
import ChangePassword from '../pages/ChangePassword';
import ForgotPassword from '../pages/ForgotPassword';
import ResetPassword from '../pages/ResetPassword';
import UserManagement from '../pages/system/UserManagement';
import RegionManagement from '../pages/system/RegionManagement';
import DepartmentManagement from '../pages/system/DepartmentManagement';
import CustomerManagement from '../pages/customer/CustomerManagement';
import ProductManagement from '../pages/product/ProductManagement';
import ProcessSelectionWrapper from '../pages/product/ProcessSelectionWrapper';
import DataSourceList from '../pages/datasource/DataSourceList';
import DataSourceEdit from '../pages/datasource/DataSourceEdit';
import ComponentManagement from '../pages/component/ComponentManagement';
import ComponentManagementHub from '../pages/component/ComponentManagementHub';
import ProductHubPage from '../pages/product/ProductHubPage';
import MasterDataHubPage from '../pages/master-data/MasterDataHubPage';
import TemplateList from '../pages/template/TemplateList';
import TemplateConfiguration from '../pages/template/TemplateConfiguration';
import ProductTemplateBinding from '../pages/template/ProductTemplateBinding';
import TemplateComparison from '../pages/template/TemplateComparison';
import PricingStrategy from '../pages/pricing/PricingStrategy';
import QuotationList from '../pages/quotation/QuotationList';
import QuotationWizard from '../pages/quotation/QuotationWizard';
import QuotationDetail from '../pages/quotation/QuotationDetail';
import ApprovalRuleManagement from '../pages/system/ApprovalRuleManagement';
import NotificationList from '../pages/system/NotificationList';
import OperationLogList from '../pages/system/OperationLogList';
import InternalMaterialManagement from '../pages/material/InternalMaterialManagement';
import ImportHistoryList from '../pages/importconfig/ImportHistoryList';
import ProductCategoryManagement from '../pages/basicdata/ProductCategoryManagement';
import ComparisonTagManagement from '../pages/basicdata/ComparisonTagManagement';
import CostingTemplateList from '../pages/costing/CostingTemplateList';
import CostingTemplateConfig from '../pages/costing/CostingTemplateConfig';
import CostingPartDataPage from '../pages/costingpart/CostingPartDataPage';
import CostingSummaryListPage from '../pages/costingsummary/CostingSummaryListPage';
import CostingSummaryDetailPage from '../pages/costingsummary/CostingSummaryDetailPage';
import CostingOrderListPage from '../pages/costingorder/CostingOrderListPage';
import MasterDataPage from '../pages/master-data/MasterDataPage';
import MasterDataTableViewerPage from '../pages/master-data/MasterDataTableViewerPage';
import VersionHistoryPage from '../pages/master-data/VersionHistoryPage';
import ChangeLogCenterPage from '../pages/change-log/ChangeLogCenterPage';
import ElementPriceCenterPage from '../pages/element-price/ElementPriceCenterPage';
import GlobalVariablePage from '../pages/global-variable/GlobalVariablePage';
import SystemConfigPage from '../pages/system-config/SystemConfigPage';
import LockMonitorPage from '../pages/system-monitor/LockMonitorPage';
import DdlExtensionPage from '../pages/system-monitor/DdlExtensionPage';
import PartVersionPage from '../pages/partversion/PartVersionPage';
import MaterialRecipeManagement from '../pages/config/MaterialRecipeManagement';
import ConfigTemplateManagement from '../pages/configtemplate/ConfigTemplateManagement';

// v0.4 3D 选配 — P0+P1+P2 端到端骨架
import FeatureLibraryList from '../pages/feature-library/FeatureLibraryList';
import FeatureGroupDetail from '../pages/feature-library/FeatureGroupDetail';
import ConfiguratorTemplateList from '../pages/configurator/ConfiguratorTemplateList';
import ConfiguratorTemplateEditor from '../pages/configurator/ConfiguratorTemplateEditor';
import ConfiguratorInstanceList from '../pages/configurator/ConfiguratorInstanceList';
import ConfiguratorInstanceDetail from '../pages/configurator/ConfiguratorInstanceDetail';
import PublicConfigurator from '../pages/public/PublicConfigurator';
import ConfiguratorStartPage from '../pages/configurator/ConfiguratorStartPage';
import ConfiguratorPage from '../pages/configurator/ConfiguratorPage';
import ConfiguratorSharesPage from '../pages/configurator/ConfiguratorSharesPage';
import CustomerLeadList from '../pages/customer-lead/CustomerLeadList';
import PartModelList from '../pages/part-model/PartModelList';

const router = createBrowserRouter([
  { path: '/login', element: <Login /> },
  // 公网客户自助选配（无 AuthGuard）
  { path: '/share/configurator/:token', element: <PublicConfigurator /> },
  { path: '/forgot-password', element: <ForgotPassword /> },
  { path: '/reset-password', element: <ResetPassword /> },
  {
    path: '/',
    element: <AuthGuard><MainLayout /></AuthGuard>,
    children: [
      { index: true, element: <Navigate to="/dashboard" replace /> },
      { path: 'dashboard', element: <Dashboard /> },
      { path: 'change-password', element: <ChangePassword /> },
      { path: 'system/users', element: <UserManagement /> },
      { path: 'system/regions', element: <RegionManagement /> },
      { path: 'system/departments', element: <DepartmentManagement /> },
      { path: 'system/approval-rules', element: <ApprovalRuleManagement /> },
      { path: 'system/notifications', element: <NotificationList /> },
      { path: 'system/operation-logs', element: <OperationLogList /> },
      { path: 'customers', element: <CustomerManagement /> },
      { path: 'products', element: <ProductManagement /> },
      { path: 'products/:productId/processes', element: <ProcessSelectionWrapper /> },
      { path: 'datasources', element: <DataSourceList /> },
      { path: 'datasources/new', element: <DataSourceEdit /> },
      { path: 'datasources/:id/edit', element: <DataSourceEdit /> },
      { path: 'components', element: <ComponentManagementHub /> },
      { path: 'components-raw', element: <ComponentManagement /> },
      { path: 'products-hub', element: <ProductHubPage /> },
      { path: 'master-data-hub', element: <MasterDataHubPage /> },
      { path: 'templates', element: <TemplateList /> },
      { path: 'templates/:id', element: <TemplateConfiguration /> },
      { path: 'template-bindings', element: <ProductTemplateBinding /> },
      { path: 'template-comparison', element: <TemplateComparison /> },
      { path: 'pricing', element: <PricingStrategy /> },
      { path: 'quotations', element: <QuotationList /> },
      { path: 'quotations/new', element: <QuotationWizard /> },
      { path: 'quotations/:id', element: <QuotationDetail /> },
      { path: 'quotations/:id/edit', element: <QuotationWizard /> },
      { path: 'materials', element: <InternalMaterialManagement /> },
      { path: 'import-history', element: <ImportHistoryList /> },
      { path: 'product-categories', element: <ProductCategoryManagement /> },
      { path: 'comparison-tags', element: <ComparisonTagManagement /> },
      { path: 'costing-templates', element: <CostingTemplateList /> },
      { path: 'costing-templates/:id', element: <CostingTemplateConfig /> },
      { path: 'costing-part-data', element: <CostingPartDataPage /> },
      { path: 'costing-summary', element: <CostingOrderListPage /> },
      { path: 'costing-summary/:id', element: <CostingSummaryDetailPage /> },
      { path: 'master-data', element: <MasterDataPage /> },
      { path: 'master-data/history', element: <VersionHistoryPage /> },
      { path: 'master-data/viewer', element: <MasterDataTableViewerPage /> },
      { path: 'change-log', element: <ChangeLogCenterPage /> },
      { path: 'element-price-center', element: <ElementPriceCenterPage /> },
      { path: 'global-variables', element: <GlobalVariablePage /> },
      { path: 'system-config', element: <SystemConfigPage /> },
      { path: 'system-monitor/locks', element: <LockMonitorPage /> },
      { path: 'system-monitor/ddl-extension', element: <DdlExtensionPage /> },
      { path: 'part-versions', element: <PartVersionPage /> },
      { path: 'config/material-recipes', element: <MaterialRecipeManagement /> },
      { path: 'config/config-templates', element: <ConfigTemplateManagement /> },

      // v0.4 3D 选配 — 销售路径
      { path: 'configurator/start', element: <ConfiguratorStartPage /> },
      { path: 'configurator/instances', element: <ConfiguratorInstanceList /> },
      { path: 'configurator/instances/:id', element: <ConfiguratorInstanceDetail /> },
      { path: 'configurator/shares', element: <ConfiguratorSharesPage /> },
      { path: 'configurator/run/:templateId', element: <ConfiguratorPage /> },

      // v0.4 3D 选配 — 管理路径（系统设置下）
      { path: 'system/configurator-templates', element: <ConfiguratorTemplateList /> },
      { path: 'system/configurator-templates/:id', element: <ConfiguratorTemplateEditor /> },
      { path: 'system/feature-library', element: <FeatureLibraryList /> },
      { path: 'system/feature-library/:groupId', element: <FeatureGroupDetail /> },
      { path: 'system/customer-leads', element: <CustomerLeadList /> },
      { path: 'system/part-models', element: <PartModelList /> },
    ],
  },
]);

export default router;
