easy-db-lab observability export: tenant __TENANT__
Written by easy-db-lab __TOOL_VERSION__.

This directory is a complete local observability stack: Grafana, Mimir, Loki and Tempo over a copy
of the tenant's metrics, logs and traces, with its dashboards, annotations and reports. It needs no
network, no AWS credentials and no cluster.

Open it
-------

    cd __ROOT__
    docker compose up -d

Then open Grafana at __GRAFANA_URL__ (no login). Stop it with "docker compose down".

The copied data is in data/ and is mounted read-only into every component, so opening the stack
never changes it. The components keep their working state in cache/ and grafana-data/.

Images
------

The stack pulls nothing it does not already have. On a machine with no network, pull these images
first, while you still have one:

__IMAGES__

What is not included
--------------------

Profiles are not included (issue #__PROFILES_ISSUE__).
