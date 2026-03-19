# Easy-DB-Lab Environment Debugging Skill

This skill provides comprehensive debugging capabilities for easy-db-lab environments, helping diagnose and fix cluster issues, service failures, connectivity problems, and configuration issues.

## Skill Structure

```
debug-environment/
├── SKILL.md                           # Main skill file with debugging instructions
├── README.md                          # This file - skill documentation
├── templates/
│   └── debug-report.md                # Template for structured debug reports
└── reference/
    ├── common-issues.md               # Common problems and solutions
    └── commands.md                    # Easy-db-lab command reference
```

## Usage

### Manual Invocation

Run the skill directly with a slash command:

```bash
# Debug the current environment
/debug-environment

# Debug a specific environment (future enhancement)
/debug-environment my-cluster-name
```

### Automatic Invocation

Claude will automatically invoke this skill when you describe debugging-related issues:

- "My easy-db-lab cluster isn't starting up correctly"
- "I'm having connectivity issues with the database nodes"
- "Grafana isn't showing any data"
- "The observability stack pods are crashing"
- "I can't SSH to the control node"
- "Kubernetes pods are in CrashLoopBackOff"

## What This Skill Does

### 1. Environment Verification
- Checks for required files (`sshConfig`, `kubeconfig`, `state.json`)
- Validates SSH connectivity to cluster nodes
- Verifies Kubernetes cluster accessibility
- Reviews cluster state and configuration

### 2. Service Health Checks
- Lists and checks Kubernetes pods
- Verifies systemd services on nodes
- Checks observability stack components
- Monitors resource usage (CPU, memory, disk)

### 3. Log Analysis
- Collects logs from multiple sources
- Analyzes error messages
- Checks Kubernetes events
- Reviews systemd journal entries

### 4. Connectivity Testing
- Tests SSH connections to all nodes
- Verifies K8s API accessibility
- Checks network connectivity
- Validates service endpoints

### 5. Root Cause Analysis
- Identifies problem patterns
- Correlates symptoms across services
- Considers recent code changes (if not on main branch)
- Provides evidence-based diagnosis

### 6. Solution Recommendations
- Suggests specific fixes
- Provides commands to execute
- Offers alternative approaches
- Includes verification steps

## Key Features

### Context-Aware Debugging

The skill understands important context:
- **Clusters are NEVER long-running** - Issues are not due to old config
- **Branch awareness** - May be due to recent code changes if not on main
- **Configuration-first** - Never suggests disabling functionality
- **Fix the root cause** - Provides proper fixes, not workarounds

### Comprehensive Coverage

Handles multiple issue categories:
- SSH and connectivity problems
- Kubernetes pod failures
- Observability stack issues
- Database-specific problems
- Configuration and build errors
- AWS/cloud infrastructure issues
- Network and resource problems

### Structured Output

Produces well-organized debug reports:
1. Environment summary
2. Issue description
3. Evidence collected
4. Diagnostic findings
5. Root cause analysis
6. Recommended fix
7. Verification steps
8. Prevention measures

## Reference Materials

### Common Issues Guide
See `reference/common-issues.md` for detailed troubleshooting of:
- SSH connection failures
- Kubernetes pod issues (Pending, CrashLoopBackOff, ImagePullBackOff)
- Observability stack problems (Grafana, VictoriaMetrics, VictoriaLogs, Tempo)
- Database-specific issues (Cassandra, ClickHouse, OpenSearch)
- Configuration and build problems
- AWS/cloud infrastructure issues
- Network and resource constraints

### Commands Reference
See `reference/commands.md` for:
- Complete easy-db-lab command reference
- Direct SSH, kubectl, systemd, and docker commands
- Common debugging workflows
- Usage examples and tips

### Debug Report Template
See `templates/debug-report.md` for the structured format used in debug outputs.

## Direct Access to Tools

This skill has access to:

### Easy-DB-Lab Commands
All easy-db-lab commands via:
```bash
java -jar build/libs/easy-db-lab-*-all.jar <command>
```

### SSH Access
Direct node access via sshConfig:
```bash
ssh -F sshConfig <node-name>
```

### Kubernetes Access
K8s cluster access via kubeconfig:
```bash
export KUBECONFIG=$(pwd)/kubeconfig
kubectl <command>
```

### File System
Read cluster state and configuration:
- `state.json` - Cluster metadata
- `sshConfig` - SSH configuration
- `kubeconfig` - Kubernetes configuration

## Typical Workflow

1. **Initial Assessment**
   - Verify environment files exist
   - Check cluster state from state.json
   - Test basic connectivity (SSH, K8s)

2. **Problem Identification**
   - Gather symptoms
   - Collect logs and error messages
   - Check service status

3. **Deep Dive**
   - SSH to affected nodes
   - Examine specific services
   - Review recent code changes if not on main

4. **Diagnosis**
   - Analyze collected evidence
   - Identify root cause
   - Rule out common issues

5. **Solution**
   - Provide specific fix steps
   - Offer alternative approaches
   - Include verification commands

6. **Prevention**
   - Suggest improvements
   - Recommend tests
   - Document findings

## Best Practices

### When Using This Skill

1. **Start at the environment directory** - Ensure you're in the directory with `sshConfig`, `kubeconfig`, and `state.json`

2. **Describe the symptoms** - Be specific about what's not working:
   - "Grafana shows no metrics data"
   - "Cannot SSH to db-0 node"
   - "Pods stuck in Pending state"

3. **Mention recent changes** - If you made code or config changes:
   - "After updating the VictoriaMetrics configuration..."
   - "Since modifying the K8s manifest builder..."

4. **Provide error messages** - If you have specific errors, include them:
   - "Getting 'connection refused' when accessing Grafana"
   - "Seeing 'OOMKilled' in pod status"

### Interpreting Results

The skill provides:
- **Evidence** - Raw data from logs, status commands, etc.
- **Analysis** - Interpretation of what the evidence means
- **Recommendations** - Specific actions to take
- **Verification** - How to confirm the fix worked

Follow the recommendations sequentially and verify after each step.

## Limitations

- Cannot create new clusters or destroy existing ones
- Cannot modify code or configuration automatically
- Cannot execute commands requiring user approval without explicit permission
- Best suited for runtime issues, not build-time problems

## Examples

### Example 1: Pod Startup Issues

**User:** "My VictoriaMetrics pod won't start"

**Skill Actions:**
1. Checks kubeconfig and K8s connectivity
2. Lists pods in monitoring namespace
3. Describes the VictoriaMetrics pod
4. Checks pod logs and events
5. Identifies root cause (e.g., ConfigMap missing)
6. Provides fix (create ConfigMap or fix reference)
7. Includes verification steps

### Example 2: SSH Connection Failure

**User:** "Can't SSH to the control node"

**Skill Actions:**
1. Checks sshConfig exists
2. Reviews state.json for control node IP
3. Tests SSH connectivity with verbose output
4. Checks security group rules (via state.json)
5. Identifies cause (e.g., wrong IP, security group)
6. Provides fix
7. Verifies connection works

### Example 3: Recent Code Change Broke Deployment

**User:** "After my changes, Grafana dashboards aren't loading"

**Skill Actions:**
1. Checks current branch (not main)
2. Reviews git diff for configuration changes
3. Identifies modified dashboard JSON
4. Validates JSON syntax
5. Checks Grafana pod logs for errors
6. Finds root cause (syntax error in dashboard)
7. Provides fix and rebuild steps

## Extending This Skill

To add new debugging capabilities:

1. **Update SKILL.md** - Add new diagnostic steps
2. **Update common-issues.md** - Add new issue patterns and solutions
3. **Update commands.md** - Document new commands or workflows
4. **Add reference files** - Create new reference docs if needed

## Related Documentation

- **Project CLAUDE.md** - Architecture and design patterns
- **Subdirectory CLAUDE.md files** - Component-specific patterns
- **docs/** - User-facing documentation
- **openspec/specs/** - Product specifications

## Support

If this skill doesn't solve your issue:
1. Check project documentation in `docs/`
2. Review CLAUDE.md files for architecture context
3. Check openspec specifications for feature requirements
4. Review recent commits and PRs
5. Check CI/CD logs for build/test failures

---

**Maintained by:** easy-db-lab project
**Last Updated:** 2026-03-19
**Skill Version:** 1.0.0
