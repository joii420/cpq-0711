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
import BasicDataConfig from '../pages/basicdata/BasicDataConfig';
import CostingTemplateList from '../pages/costing/CostingTemplateList';
import CostingTemplateConfig from '../pages/costing/CostingTemplateConfig';
import CostingPartDataPage from '../pages/costingpart/CostingPartDataPage';
import CostingSummaryListPage from '../pages/costingsummary/CostingSummaryListPage';
import CostingSummaryDetailPage from '../pages/costingsummary/CostingSummaryDetailPage';
import MasterDataPage from '../pages/master-data/MasterDataPage';
import VersionHistoryPage from '../pages/master-data/VersionHistoryPage';
import ChangeLogCenterPage from '../pages/change-log/ChangeLogCenterPage';
import ElementPriceCenterPage from '../pages/element-price/ElementPriceCenterPage';
import GlobalVariablePage from '../pages/global-variable/GlobalVariablePage';
import SystemConfigPage from '../pages/system-config/SystemConfigPage';
import FieldImportancePage from '../pages/master-data/FieldImportancePage';
import LockMonitorPage from '../pages/system-monitor/LockMonitorPage';
import DdlExtensionPage from '../pages/system-monitor/DdlExtensionPage';
import PartVersionPage from '../pages/partversion/PartVersionPage';
import MaterialRecipeManagement from '../pages/config/MaterialRecipeManagement';
import ProcessManagement from '../pages/config/ProcessManagement';

const router = createBrowserRouter([
  { path: '/login', element: <Login /> },
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
      { path: 'components', element: <ComponentManagement /> },
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
      { path: 'basic-data-config', element: <BasicDataConfig /> },
      { path: 'costing-templates', element: <CostingTemplateList /> },
      { path: 'costing-templates/:id', element: <CostingTemplateConfig /> },
      { path: 'costing-part-data', element: <CostingPartDataPage /> },
      { path: 'costing-summary', element: <CostingSummaryListPage /> },
      { path: 'costing-summary/:id', element: <CostingSummaryDetailPage /> },
      { path: 'master-data', element: <MasterDataPage /> },
      { path: 'master-data/history', element: <VersionHistoryPage /> },
      { path: 'change-log', element: <ChangeLogCenterPage /> },
      { path: 'element-price-center', element: <ElementPriceCenterPage /> },
      { path: 'global-variables', element: <GlobalVariablePage /> },
      { path: 'system-config', element: <SystemConfigPage /> },
      { path: 'master-data/field-importance', element: <FieldImportancePage /> },
      { path: 'system-monitor/locks', element: <LockMonitorPage /> },
      { path: 'system-monitor/ddl-extension', element: <DdlExtensionPage /> },
      { path: 'part-versions', element: <PartVersionPage /> },
      { path: 'config/material-recipes', element: <MaterialRecipeManagement /> },
      { path: 'config/processes', element: <ProcessManagement /> },
    ],
  },
]);

export default router;
