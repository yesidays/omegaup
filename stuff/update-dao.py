#!/usr/bin/python3
# pylint: disable=invalid-name
# This program is intended to be invoked from the console, not to be used as a
# module.
'''A tool that calls the DAO updater.'''

from __future__ import print_function

import argparse
import os

import dao_utils

_OMEGAUP_ROOT = os.path.abspath(os.path.join(__file__, '..', '..'))


def _main():
    parser = argparse.ArgumentParser()
    parser.add_argument(
        '--script',
        type=argparse.FileType('r'),
        default=os.path.join(_OMEGAUP_ROOT, 'frontend/database/schema.sql'),
    )
    args = parser.parse_args()

    for filename, contents in dao_utils.generate_dao(args.script.read()):
        with open(
                os.path.join(_OMEGAUP_ROOT, 'frontend/server/libs/dao/base',
                             filename), 'w') as f:
            f.write(contents)


if __name__ == '__main__':
    _main()

# vim: tabstop=4 expandtab shiftwidth=4 softtabstop=4