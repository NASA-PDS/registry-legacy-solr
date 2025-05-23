#!/usr/bin/env python
import logging
import os
import re

from pandas import read_csv
from argparse import ArgumentParser, RawDescriptionHelpFormatter
from typing import TextIO
from uuid import uuid4

from solr_to_es.solrSource import SlowSolrDocs  # type: ignore
from xml.sax.saxutils import escape

# Constants
SKIP_FIELDS = ['score', 'timestamp', 'search_id']
SOLR_URL = "https://pds.nasa.gov/services/search/search"
MAX_RETRIES = 5
DOCS_PER_FILE = 1000
SOLR_DOC_PREFIX = "solr_doc_deprecated"
PACKAGE_ID = uuid4()

# Globals
_known_pds4_dict = {}
_missing_context = {'target_ref': {}, 'instrument_ref': {}, 'instrument_host_ref': {}, 'investigation_ref': {}}

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

def open_doc(output_path, file_counter):
    output_filepath = os.path.join(output_path, f"{SOLR_DOC_PREFIX}.{str(file_counter)}.xml")
    solr_doc = open(output_filepath, "w")
    logger.info("Writing solr doc: " + output_filepath)
    solr_doc.write("<add>\n")
    return solr_doc

def close_doc(solr_doc):
    if solr_doc:
        solr_doc.write("</add>\n")
        solr_doc.close()

def run(query: str, db_path: str, output_path: str):
    doc_counter = 0
    file_counter = 0
    current_solr_doc = None

    # Init PDS3 database dict
    known_database_dict = init_db(db_path)

    solr_itr = SlowSolrDocs(SOLR_URL, query, rows=500)
    for doc in solr_itr:
        # Increment file counter, as needed
        if doc_counter % DOCS_PER_FILE == 0:
            close_doc(current_solr_doc)
            file_counter += 1
            current_solr_doc = open_doc(output_path, file_counter)

        current_solr_doc.write("<doc>\n")
        current_solr_doc.write(f"<field name='package_id'>{PACKAGE_ID}</field>\n")

        is_data_set = True
        if 'data_class' in doc and 'Resource' in doc['data_class']:
            is_data_set = False

        for key in doc:
            if key == 'identifier':
                current_solr_doc.write(f"<field name='lid'>{doc[key]}</field>\n")
            else:
                if not isinstance(doc[key], list):
                    doc[key] = [doc[key]]

                for value in doc[key]:
                    if key == 'file_ref_url':
                        value = value.replace('http://starbase.jpl.nasa.gov', 'https://pds.nasa.gov/data')
                    elif is_data_set:
                        if key == 'investigation_name':
                            write_ref(current_solr_doc, known_database_dict, value, 'investigation_ref', 'facet_investigation_name', doc['data_set_id'][0])
                        elif key == 'instrument_host_name':
                            write_ref(current_solr_doc, known_database_dict, value, 'instrument_host_ref', 'facet_instrument_host_name', doc['data_set_id'][0])
                        elif key == 'instrument_name':
                            write_ref(current_solr_doc, known_database_dict, value, 'instrument_ref', 'facet_instrument_name', doc['data_set_id'][0])
                        elif key == 'target_name':
                            # Assume write_success because of how many missing target context products
                            write_ref(current_solr_doc, known_database_dict, value, 'target_ref', 'facet_target_name', doc['data_set_id'][0])

                    if isinstance(value, str):
                        value = sanitize(value)

                    current_solr_doc.write(f"<field name='{key}'>{value}</field>\n")

        current_solr_doc.write("</doc>\n")
        doc_counter += 1

    close_doc(current_solr_doc)

    write_missing_context()

def write_ref(current_solr_doc: TextIO, known_database_dict: dict, value: str, ref_name: str, facet_field_name: str, data_set_id: str):
    pds4_identifiers = known_database_dict.get(value)

    if not pds4_identifiers:
        # try appending data set id
        pds4_identifiers = known_database_dict.get(f'{value} - {data_set_id}')

    if pds4_identifiers:
        pds4_id_list = pds4_identifiers.split(",")
        for pds4_id in pds4_id_list:
            current_solr_doc.write(
                f"<field name='{ref_name}'>{pds4_id}</field>\n")

            pds4_name = sanitize(get_pds4_name(pds4_id))

            current_solr_doc.write(f"<field name='{facet_field_name}'>{pds4_name}</field>\n")

            return True
    else:
        if value not in _missing_context[ref_name].keys():
            _missing_context[ref_name][value] = [data_set_id]
        else:
            _missing_context[ref_name][value].append(data_set_id)

        # Log the missing ref if it is not a target
        if ref_name != 'target_ref' and not data_set_id.startswith('EAR-') and not data_set_id.startswith('RO-') and not data_set_id.startswith('MULTI-') and not data_set_id.startswith('IHW-'):
            logger.info(f"Missing ref: {value} - {data_set_id}")

def get_pds4_name(identifier: str) -> str:
    name = _known_pds4_dict.get(identifier)
    if not name:
        # Let's loop through keys to check those with multiple identifiers
        for key in _known_pds4_dict.keys():
            if identifier in key:
                name = _known_pds4_dict[key]

        if not name:
            logger.error(f"Missing identifier: {identifier}")

    return name

def search_solr(identifier: str) -> str:
    name = None
    if identifier == 'rn:nasa:pds:context:facility:observatory.leura':
        identifier = 'urn:nasa:pds:context:facility:observatory.leura'
    elif identifier == 'urn:nasa:pds:context:investigation:observing_campaign.saturn_occultation_of_28_sagittarius_1989':
        name = 'Saturn Occultation of 28 Sagittarius 1989'
        return name

    query = f"identifier:\"{identifier}\""

    solr_itr = SlowSolrDocs(SOLR_URL, query, rows=500)
    for doc in solr_itr:
        name = doc['title']

    if not name:
        logger.error(f"Missing identifier: {identifier}")

    return name

def sanitize(value: str) -> str:
    value = escape(value)
    value = re.sub(' +', ' ', value)
    return re.sub('=+', ':', value)

def init_db(db_path: str) -> dict:
    known_database_df = read_csv(db_path)
    _known_pds3_objects = known_database_df['pds3_name'].tolist()
    _known_identifiers = known_database_df['identifier'].tolist()
    known_pds4_names = known_database_df['pds4_name'].tolist()

    global _known_pds4_dict
    _known_pds4_dict = dict(zip(_known_identifiers, known_pds4_names))
    return dict(zip(_known_pds3_objects, _known_identifiers))

def write_missing_context():
    with open('/Users/jpadams/missing_context.csv', 'w') as f:
        # Write all the dictionary keys in a file with commas separated.
        f.write('reference_type,pds3_name,data_set_ids')
        logger.info("Writing missing_context")
        for reference_type in _missing_context:
            logger.info(reference_type)
            for pds3_name in _missing_context[reference_type]:
                logger.info(pds3_name)
                data_set_id_value = ''
                for data_set_id in _missing_context[reference_type][pds3_name]:
                    logger.info(data_set_id)
                    data_set_id_value += f'{data_set_id}\n'

                f.write(f'"{reference_type}","{pds3_name}","{data_set_id_value}"\n')

def main():
    """main"""
    parser = ArgumentParser(formatter_class=RawDescriptionHelpFormatter,
                            description=__doc__)

    parser.add_argument('--db-path',
                        help='file path to CSV of known PDS3 names to PDS4 identifiers',
                        required=True)

    parser.add_argument('--output-path',
                        help='path to output solr docs',
                        default='.')

    parser.add_argument('--query',
                        help='solr query',
                        default='product_class:Product_Data_Set_PDS3 OR data_class:Resource')

    args = parser.parse_args()

    run(args.query, args.db_path, args.output_path)

if __name__ == '__main__':
    main()