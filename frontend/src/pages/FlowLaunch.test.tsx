import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import FlowLaunch from './FlowLaunch';

const definitions = [
  {
    id: '11111111-1111-1111-1111-111111111111',
    name: 'customer-onboarding',
    version: 2,
    status: 'PUBLISHED',
    active: true,
    description: 'Primary onboarding flow',
  },
  {
    id: '22222222-2222-2222-2222-222222222222',
    name: 'payments-escalation',
    version: 1,
    status: 'PUBLISHED',
    active: true,
    description: 'Escalation flow for payments team',
  },
];

const previewMap = {
  '11111111-1111-1111-1111-111111111111': {
    definitionId: definitions[0].id,
    definitionName: definitions[0].name,
    definitionVersion: definitions[0].version,
    description: definitions[0].description,
    startStepId: 'collect-context',
    steps: [
      {
        id: 'collect-context',
        name: 'Collect Context',
        prompt: 'Collect customer information.',
        agent: {
          agentVersionId: 'aaaaaaaa-1111-2222-3333-444444444444',
          agentVersionNumber: 5,
          agentDefinitionId: 'bbbbbbbb-1111-2222-3333-444444444444',
          agentIdentifier: 'context-collector',
          agentDisplayName: 'Context Collector',
          providerType: 'OPENAI',
          providerId: 'openai',
          providerDisplayName: 'OpenAI',
          modelId: 'gpt-4o-mini',
          modelDisplayName: 'GPT-4o Mini',
          modelContextWindow: 128000,
          modelMaxOutputTokens: 4096,
          syncOnly: false,
          maxTokens: null,
          defaultOptions: null,
          costProfile: null,
          pricing: {
            inputPer1KTokens: 0.0025,
            outputPer1KTokens: 0.01,
            currency: 'USD',
          },
        },
        overrides: {
          temperature: 0.2,
          topP: null,
          maxTokens: 512,
        },
        memoryReads: [
          { channel: 'customer-profile', limit: 5 },
        ],
        memoryWrites: [
          { channel: 'shared-context', mode: 'AGENT_OUTPUT', payload: null },
        ],
        transitions: {
          onSuccess: 'summarise',
          completeOnSuccess: false,
          onFailure: null,
          failFlowOnFailure: true,
        },
        maxAttempts: 2,
        estimate: {
          promptTokens: 120,
          completionTokens: 512,
          totalTokens: 632,
          inputCost: 0.0003,
          outputCost: 0.00512,
          totalCost: 0.00542,
          currency: 'USD',
        },
      },
    ],
    totalEstimate: {
      promptTokens: 120,
      completionTokens: 512,
      totalTokens: 632,
      inputCost: 0.0003,
      outputCost: 0.00512,
      totalCost: 0.00542,
      currency: 'USD',
    },
  },
  '22222222-2222-2222-2222-222222222222': {
    definitionId: definitions[1].id,
    definitionName: definitions[1].name,
    definitionVersion: definitions[1].version,
    description: definitions[1].description,
    startStepId: 'triage',
    steps: [
      {
        id: 'triage',
        name: 'Initial triage',
        prompt: 'Ask about payment incident.',
        agent: {
          agentVersionId: 'cccccccc-1111-2222-3333-444444444444',
          agentVersionNumber: 3,
          agentDefinitionId: 'dddddddd-1111-2222-3333-444444444444',
          agentIdentifier: 'payments-specialist',
          agentDisplayName: 'Payments Specialist',
          providerType: 'AZURE_OPENAI',
          providerId: 'azure-openai',
          providerDisplayName: 'Azure OpenAI',
          modelId: 'gpt-4o-mini',
          modelDisplayName: 'GPT-4o Mini',
          modelContextWindow: 64000,
          modelMaxOutputTokens: 2048,
          syncOnly: true,
          maxTokens: 1024,
          defaultOptions: null,
          costProfile: null,
          pricing: {
            inputPer1KTokens: 0.003,
            outputPer1KTokens: 0.012,
            currency: 'USD',
          },
        },
        overrides: null,
        memoryReads: [],
        memoryWrites: [],
        transitions: {
          onSuccess: null,
          completeOnSuccess: true,
          onFailure: null,
          failFlowOnFailure: true,
        },
        maxAttempts: 1,
        estimate: {
          promptTokens: 80,
          completionTokens: 400,
          totalTokens: 480,
          inputCost: 0.00024,
          outputCost: 0.0048,
          totalCost: 0.00504,
          currency: 'USD',
        },
      },
    ],
    totalEstimate: {
      promptTokens: 80,
      completionTokens: 400,
      totalTokens: 480,
      inputCost: 0.00024,
      outputCost: 0.0048,
      totalCost: 0.00504,
      currency: 'USD',
    },
  },
};

vi.mock('../lib/apiClient', async () => {
  const actual = await vi.importActual<typeof import('../lib/apiClient')>('../lib/apiClient');

  return {
    ...actual,
    fetchFlowDefinitions: vi.fn(async () => definitions),
    fetchFlowLaunchPreview: vi.fn(async (definitionId: string) => previewMap[definitionId as keyof typeof previewMap]),
    startFlow: vi.fn(async () => ({
      sessionId: '99999999-9999-9999-9999-999999999999',
      status: 'RUNNING',
      startedAt: '2025-01-01T10:00:00Z',
    })),
  };
});

describe('FlowLaunch', () => {
  afterEach(() => {
    vi.clearAllMocks();
  });

  it('renders preview and starts flow with parsed payload', async () => {
    const { fetchFlowLaunchPreview, startFlow } = await import('../lib/apiClient');

    render(
      <MemoryRouter initialEntries={['/flows/launch?definitionId=11111111-1111-1111-1111-111111111111']}>
        <Routes>
          <Route path="/flows/launch" element={<FlowLaunch />} />
        </Routes>
      </MemoryRouter>,
    );

    await waitFor(() => {
      expect(fetchFlowLaunchPreview).toHaveBeenCalledWith('11111111-1111-1111-1111-111111111111');
    });

    expect(
      screen.getByText(/customer-onboarding · v2/i),
    ).toBeInTheDocument();

    expect(screen.getByText('632')).toBeInTheDocument();
    expect(screen.getByText('0.01 USD', { exact: false })).toBeInTheDocument();

    const stepCard = screen.getByText('Collect Context').closest('article');
    expect(stepCard).not.toBeNull();
    if (stepCard) {
      expect(within(stepCard).getByText(/Context Collector/)).toBeInTheDocument();
      expect(stepCard).toHaveTextContent(/Макс\. попыток:\s*2/);
      expect(stepCard).toHaveTextContent(/temperature:\s*0\.2/i);
    }

    const select = screen.getByRole('combobox', { name: /определение/i });
    await userEvent.selectOptions(select, definitions[1].id);

    await waitFor(() => {
      expect(fetchFlowLaunchPreview).toHaveBeenLastCalledWith(definitions[1].id);
    });
    expect((select as HTMLSelectElement).value).toBe(definitions[1].id);

    const parametersField = screen.getByLabelText(/Parameters/i);
    fireEvent.change(parametersField, {
      target: { value: '{ "customerId": "123" }' },
    });

    const sharedContextField = screen.getByLabelText(/Shared context/i);
    fireEvent.change(sharedContextField, {
      target: { value: '{ "notes": ["urgent"] }' },
    });

    await userEvent.click(screen.getByRole('button', { name: /Запустить флоу/i }));

    await waitFor(() => {
      expect(startFlow).toHaveBeenCalledWith(definitions[1].id, {
        parameters: { customerId: '123' },
        sharedContext: { notes: ['urgent'] },
      });
    });
  });
});
