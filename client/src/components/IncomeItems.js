import React from 'react';
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome';
import { faTrash } from '@fortawesome/free-solid-svg-icons';
import axios from 'axios';

function IncomeItems({income, setIncomeData, currentDate}) {

    const handleDelete = async (event) => {
        const id = event.target.id;
        await axios.delete(`/api/income/${id}`)
        axios.get(`/api/users/income/${currentDate.month}/${currentDate.year}`).then((response) => setIncomeData(response.data.incomes)).catch(err => {
          console.log(err);
          setIncomeData([{name: 'No Income Yet!'}])
        })
    }

    

  return (
    <>
       <tr>
           <td>{income.name}</td>
           <td>{income.amount}</td>
           <td onClick={handleDelete} className="trashbtn" id={income.id}><FontAwesomeIcon icon={faTrash} style={{pointerEvents: 'none'}} /></td>
       </tr>
    </>
  );
}

export default IncomeItems;
